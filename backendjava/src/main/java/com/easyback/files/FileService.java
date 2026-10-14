package com.easyback.files;

import com.easyback.identity.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class FileService {
    public enum Kind { ORDER,MACHINE,PART,DEFECT }
    public record Metadata(UUID id,Kind targetKind,UUID targetId,String filename,String contentType,int sizeBytes,long version,String uploadedBy,Instant uploadedAt,boolean canManage) {}
    public record Content(Metadata file,byte[] bytes) {}
    private final JdbcClient db; private final IdentityStore identity;
    public FileService(JdbcClient db,IdentityStore identity) { this.db=db; this.identity=identity; }
    private static ApiException missing() { return new ApiException(404,"NOT_FOUND","File or target not found."); }
    private void guard() {
        db.sql("SELECT id FROM security_guard WHERE id=1 FOR UPDATE").query(Integer.class).single();
        db.sql("SELECT id FROM catalog_guard WHERE id=1 FOR UPDATE").query(Integer.class).single();
    }
    private boolean ownTask(String column,UUID id,UUID actor) {
        return db.sql("SELECT count(*) FROM assembly_tasks t JOIN order_requirements r ON r.id=t.requirement_id JOIN manufacturing_orders o ON o.id=r.order_id WHERE t.worker_id=:actor AND "+column+"=:id")
            .param("id",id).param("actor",actor).query(Long.class).single()>0;
    }
    private boolean access(Kind kind,UUID id,UUID actor) {
        var p=identity.permissions(actor); boolean own=p.contains("tasks.view-assigned"); boolean allowed;
        switch(kind) {
            case ORDER -> allowed=p.contains("orders.view") || (p.contains("orders.view-assigned") && db.sql("SELECT count(*) FROM manufacturing_orders WHERE id=:id AND responsible_id=:actor").param("id",id).param("actor",actor).query(Long.class).single()>0) || (own && ownTask("o.id",id,actor));
            case MACHINE -> allowed=p.contains("machines.view") || (own && ownTask("o.machine_id",id,actor));
            case PART -> allowed=p.contains("catalog.view") || (own && ownTask("r.part_id",id,actor));
            case DEFECT -> allowed=(p.contains("defects.view") && p.contains("orders.view")) || ((own || p.contains("tasks.view") && p.contains("orders.view")) && db.sql("SELECT count(*) FROM defect_cases WHERE id=:id AND reported_by=:actor").param("id",id).param("actor",actor).query(Long.class).single()>0);
            default -> throw missing();
        }
        if(!allowed) throw missing();
        String table=switch(kind) { case ORDER->"manufacturing_orders"; case MACHINE->"machines"; case PART->"parts"; case DEFECT->"defect_cases"; };
        if(db.sql("SELECT count(*) FROM "+table+" WHERE id=:id").param("id",id).query(Long.class).single()!=1) throw missing();
        return p.contains("files.manage");
    }
    private Metadata map(ResultSet r,boolean manage) throws SQLException { return new Metadata(r.getObject("id",UUID.class),Kind.valueOf(r.getString("target_kind")),r.getObject("target_id",UUID.class),r.getString("filename"),r.getString("content_type"),r.getInt("size_bytes"),r.getLong("version"),r.getString("display_name"),r.getTimestamp("uploaded_at").toInstant(),manage); }
    public List<Metadata> list(Kind kind,UUID id,UUID actor) {
        boolean manage=access(kind,id,actor);
        return db.sql("SELECT f.*,u.display_name FROM resource_files f JOIN users u ON u.id=f.uploaded_by WHERE target_kind=:kind AND target_id=:id AND NOT deleted ORDER BY uploaded_at DESC,f.id")
            .param("kind",kind.name()).param("id",id).query((r,n)->map(r,manage)).list();
    }
    private Metadata metadata(UUID id,UUID actor) {
        var f=db.sql("SELECT f.*,u.display_name FROM resource_files f JOIN users u ON u.id=f.uploaded_by WHERE f.id=:id AND NOT deleted").param("id",id).query((r,n)->map(r,false)).optional().orElseThrow(FileService::missing);
        boolean manage=access(f.targetKind(),f.targetId(),actor); return new Metadata(f.id(),f.targetKind(),f.targetId(),f.filename(),f.contentType(),f.sizeBytes(),f.version(),f.uploadedBy(),f.uploadedAt(),manage);
    }
    public Content download(UUID id,UUID actor) {
        var f=metadata(id,actor); return new Content(f,db.sql("SELECT content FROM resource_files WHERE id=:id").param("id",id).query(byte[].class).single());
    }
    private String type(byte[] bytes) {
        if(bytes.length>8 && new String(bytes,0,5,StandardCharsets.US_ASCII).equals("%PDF-") && new String(bytes,Math.max(0,bytes.length-1024),Math.min(1024,bytes.length),StandardCharsets.ISO_8859_1).contains("%%EOF")) return "application/pdf";
        try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers=ImageIO.getImageReaders(input); if(!readers.hasNext()) throw new IOException(); var reader=readers.next();
            try {
                reader.setInput(input); String format=reader.getFormatName().toLowerCase(Locale.ROOT);
                if(!Set.of("png","jpeg","jpg").contains(format) || (long)reader.getWidth(0)*reader.getHeight(0)>10000000) throw new IOException();
                reader.read(0); return format.equals("png")?"image/png":"image/jpeg";
            } finally { reader.dispose(); }
        } catch(Exception e) { throw new ApiException(400,"FILE_TYPE","Only valid PDF, PNG and JPEG files are accepted."); }
    }
    @Transactional
    public Metadata upload(Kind kind,UUID target,MultipartFile file,UUID actor) throws IOException {
        guard(); if(!access(kind,target,actor)) throw new ApiException(403,"FORBIDDEN","File management permission required.");
        if(file.isEmpty() || file.getSize()>10485760) throw new ApiException(400,"FILE_SIZE","File must be between 1 byte and 10 MiB.");
        String name=Objects.toString(file.getOriginalFilename(),"").trim();
        if(name.isBlank() || name.length()>180 || name.contains("/") || name.contains("\\") || name.chars().anyMatch(c->c<32 || c==127)) throw new ApiException(400,"FILE_NAME","Invalid filename.");
        byte[] bytes=file.getBytes(); String mime=type(bytes),hash;
        try { hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        UUID id=UUID.randomUUID();
        db.sql("INSERT INTO resource_files(id,target_kind,target_id,filename,content_type,size_bytes,sha256,content,uploaded_by) VALUES(:id,:kind,:target,:name,:type,:size,:hash,:content,:actor)")
            .param("id",id).param("kind",kind.name()).param("target",target).param("name",name).param("type",mime).param("size",bytes.length).param("hash",hash).param("content",bytes).param("actor",actor).update();
        audit(actor,"FILE_UPLOADED",id); return metadata(id,actor);
    }
    private void audit(UUID actor,String action,UUID target) { db.sql("INSERT INTO audit_logs(id,actor_id,action,target_id) VALUES(:id,:actor,:action,:target)").param("id",UUID.randomUUID()).param("actor",actor).param("action",action).param("target",target).update(); }
    @Transactional
    public void delete(UUID id,long version,UUID actor) {
        guard(); var f=metadata(id,actor); if(!f.canManage()) throw new ApiException(403,"FORBIDDEN","File management permission required.");
        if(f.version()!=version) throw new ApiException(409,"STALE_VERSION","File was changed.");
        db.sql("UPDATE resource_files SET deleted=true,version=version+1 WHERE id=:id").param("id",id).update(); audit(actor,"FILE_REMOVED",id);
    }
}

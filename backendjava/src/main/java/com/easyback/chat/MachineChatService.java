package com.easyback.chat;

import com.easyback.identity.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
public class MachineChatService {
    public record Message(long id, UUID senderId, String senderName, String body, Instant createdAt) {}
    public record Page(List<Message> messages, boolean hasMore) {}
    public record Unread(UUID machineId, long count) {}
    private final JdbcClient db;
    private final IdentityStore identity;
    public MachineChatService(JdbcClient db, IdentityStore identity) { this.db=db; this.identity=identity; }
    private void permission(UUID actor) {
        if(!identity.permissions(actor).contains("machines.view")) throw new ApiException(403,"FORBIDDEN","Machine access required.");
    }
    private void access(UUID machine, UUID actor) {
        permission(actor);
        if(db.sql("SELECT count(*) FROM machines WHERE id=:id").param("id",machine).query(Long.class).single()!=1)
            throw new ApiException(404,"NOT_FOUND","Machine not found.");
    }
    public Page messages(UUID machine, Long before, UUID actor) {
        access(machine,actor);
        var rows=db.sql("""
            SELECT m.id,m.sender_id,u.display_name,m.body,m.created_at
            FROM machine_messages m JOIN users u ON u.id=m.sender_id
            WHERE m.machine_id=:machine AND m.id<:before ORDER BY m.id DESC LIMIT 51
            """).param("machine",machine).param("before",before==null?Long.MAX_VALUE:before)
            .query((r,n)->new Message(r.getLong("id"),r.getObject("sender_id",UUID.class),r.getString("display_name"),r.getString("body"),r.getTimestamp("created_at").toInstant())).list();
        var page=new ArrayList<>(rows.subList(0,Math.min(50,rows.size()))); Collections.reverse(page);
        return new Page(page,rows.size()>50);
    }
    public List<Unread> unread(UUID actor) {
        permission(actor);
        return db.sql("""
            SELECT m.machine_id,count(*) amount FROM machine_messages m
            LEFT JOIN machine_chat_reads r ON r.machine_id=m.machine_id AND r.user_id=:actor
            WHERE m.sender_id<>:actor AND m.id>coalesce(r.last_message_id,0) GROUP BY m.machine_id
            """).param("actor",actor).query((r,n)->new Unread(r.getObject("machine_id",UUID.class),r.getLong("amount"))).list();
    }
    @Transactional
    public Message send(UUID machine, String body, UUID actor) {
        // Share the identity lock with permission changes, then serialize sends for each machine.
        db.sql("SELECT id FROM security_guard WHERE id=1 FOR UPDATE").query(Integer.class).single();
        access(machine,actor);
        if(body==null || body.isBlank() || body.length()>2000) throw new ApiException(400,"VALIDATION","Message must contain 1 to 2000 characters.");
        db.sql("SELECT id FROM machines WHERE id=:id FOR UPDATE").param("id",machine).query(UUID.class).single();
        long id=db.sql("INSERT INTO machine_messages(machine_id,sender_id,body) VALUES(:machine,:actor,:body) RETURNING id")
            .param("machine",machine).param("actor",actor).param("body",body.strip()).query(Long.class).single();
        return messages(machine,null,actor).messages().stream().filter(m->m.id()==id).findFirst().orElseThrow();
    }
    @Transactional
    public void read(UUID machine, long through, UUID actor) {
        access(machine,actor);
        if(db.sql("SELECT count(*) FROM machine_messages WHERE machine_id=:machine AND id=:id").param("machine",machine).param("id",through).query(Long.class).single()!=1)
            throw new ApiException(404,"NOT_FOUND","Message not found.");
        db.sql("""
            INSERT INTO machine_chat_reads(machine_id,user_id,last_message_id) VALUES(:machine,:actor,:id)
            ON CONFLICT(machine_id,user_id) DO UPDATE SET last_message_id=greatest(machine_chat_reads.last_message_id,excluded.last_message_id)
            """).param("machine",machine).param("actor",actor).param("id",through).update();
    }
}

package com.easyback.warehouse;

import static com.easyback.warehouse.RawData.*;
import com.easyback.identity.ApiException;
import com.easyback.assembly.AssemblyService;
import java.math.BigDecimal;
import java.sql.Types;
import java.util.*;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class RawService {
    private final JdbcClient db;
    private final StockService stock;
    public RawService(JdbcClient db,StockService stock) { this.db=db; this.stock=stock; }
    private static void rule(boolean valid,String code) { if(!valid) throw new ApiException(409,code,"Raw stock operation is not allowed."); }
    private static ApiException missing() { return new ApiException(404,"NOT_FOUND","Record not found."); }
    private void guard() {
        db.sql("SELECT id FROM security_guard WHERE id=1 FOR UPDATE").query(Integer.class).single();
        db.sql("SELECT id FROM catalog_guard WHERE id=1 FOR UPDATE").query(Integer.class).single();
    }
    private void audit(UUID actor,String action,UUID target) {
        db.sql("INSERT INTO audit_logs(id,actor_id,action,target_id) VALUES (:id,:actor,:action,:target)")
            .param("id",UUID.randomUUID()).param("actor",actor).param("action",action).param("target",target).update();
    }
    private static final String UNITS="""
        SELECT u.*,p.code part_code,p.name part_name,p.stock_unit,p.shape,p.diameter_mm,parent.code parent_code,o.code order_code,r.position_code
        FROM raw_units u JOIN parts p ON p.id=u.part_id LEFT JOIN raw_units parent ON parent.id=u.parent_id
        LEFT JOIN preparation_requests q ON q.id=u.preparation_id LEFT JOIN order_requirements r ON r.id=q.requirement_id
        LEFT JOIN manufacturing_orders o ON o.id=r.order_id
        """;
    private static final RowMapper<Unit> UNIT=(r,n)->new Unit(r.getObject("id",UUID.class),r.getString("code"),r.getObject("part_id",UUID.class),r.getString("part_code"),
        r.getString("part_name"),r.getString("stock_unit"),r.getString("shape"),r.getBigDecimal("length_mm"),r.getBigDecimal("width_mm"),r.getBigDecimal("diameter_mm"),
        r.getString("location"),r.getString("status"),r.getLong("version"),r.getObject("preparation_id",UUID.class),r.getObject("parent_id",UUID.class),r.getString("parent_code"),r.getString("order_code"),r.getString("position_code"));
    public List<Unit> units() { return db.sql(UNITS+" ORDER BY u.received_at DESC,u.code").query(UNIT).list(); }
    private Unit unit(UUID id) { return db.sql(UNITS+" WHERE u.id=:id").param("id",id).query(UNIT).optional().orElseThrow(RawService::missing); }
    private record Spec(String kind,String shape,String unit,BigDecimal width,BigDecimal diameter,boolean active) {}
    private Spec spec(UUID id) {
        return db.sql("SELECT * FROM parts WHERE id=:id").param("id",id).query((r,n)->new Spec(r.getString("kind"),r.getString("shape"),r.getString("stock_unit"),r.getBigDecimal("width_mm"),r.getBigDecimal("diameter_mm"),r.getBoolean("active"))).optional().orElseThrow(RawService::missing);
    }
    private BigDecimal amount(Unit u) { return RawCutPlanner.amount(u.stockUnit(),u.lengthMm(),u.widthMm()); }
    private void movement(Unit u,String action,UUID prep,UUID actor,String reference,String notes) {
        db.sql("INSERT INTO raw_movements(id,unit_id,action,preparation_id,actor_id,amount,reference,notes) VALUES (:id,:unit,:action,:prep,:actor,:amount,:reference,:notes)")
            .param("id",UUID.randomUUID()).param("unit",u.id()).param("action",action).param("prep",prep,Types.OTHER).param("actor",actor)
            .param("amount",amount(u)).param("reference",reference.trim()).param("notes",notes.trim()).update();
    }
    private UUID insert(String code,UUID part,BigDecimal length,BigDecimal width,String location,String status,UUID parent,UUID prep) {
        UUID id=UUID.randomUUID();
        db.sql("INSERT INTO raw_units(id,code,part_id,length_mm,width_mm,location,status,parent_id,preparation_id) VALUES (:id,:code,:part,:length,:width,:location,:status,:parent,:prep)")
            .param("id",id).param("code",code).param("part",part).param("length",length).param("width",width,Types.NUMERIC).param("location",location.trim())
            .param("status",status).param("parent",parent,Types.OTHER).param("prep",prep,Types.OTHER).update(); return id;
    }
    @Transactional
    public List<Unit> receive(Receipt p,UUID actor) {
        guard(); var s=spec(p.partId()); rule(s.active(),"PART_INACTIVE"); rule(s.kind().equals("MEASURED_STOCK"),"RAW_MEASURED_ONLY");
        rule(s.unit().equals("AREA") ? p.widthMm()!=null : s.shape().equals("ROUND") ? p.widthMm()==null : same(p.widthMm(),s.width()),"RAW_PROFILE_MISMATCH");
        var codes=p.codes().stream().map(c->c.toUpperCase(Locale.ROOT)).toList(); rule(new HashSet<>(codes).size()==codes.size(),"RAW_DUPLICATE_CODE");
        var result=new ArrayList<Unit>();
        for(String code:codes) {
            rule(db.sql("SELECT count(*) FROM raw_units WHERE code=:code").param("code",code).query(Long.class).single()==0,"RAW_DUPLICATE_CODE");
            UUID id=insert(code,p.partId(),p.lengthMm(),p.widthMm(),p.location(),"AVAILABLE",null,null); var u=unit(id);
            movement(u,"RECEIVED",null,actor,p.reference(),p.notes()); result.add(u);
        }
        audit(actor,"RAW_RECEIVED",p.partId()); return result;
    }
    private record Preparation(UUID id,UUID requirement,UUID order,UUID part,long version,String status,String orderStatus,
        long missing,String shape,BigDecimal length,BigDecimal width,BigDecimal diameter) {}
    private Preparation preparation(UUID id) {
        return db.sql("""
            SELECT q.id,q.requirement_id,q.version,q.status,r.order_id,r.part_id,r.shape,r.length_mm,r.width_mm,r.diameter_mm,
              o.status order_status,greatest(0,r.quantity-(SELECT count(*) FROM stock_pieces s WHERE s.requirement_id=r.id AND s.status<>'REJECTED')) missing
            FROM preparation_requests q JOIN order_requirements r ON r.id=q.requirement_id JOIN manufacturing_orders o ON o.id=r.order_id WHERE q.id=:id
            """).param("id",id).query((r,n)->new Preparation(r.getObject("id",UUID.class),r.getObject("requirement_id",UUID.class),r.getObject("order_id",UUID.class),
                r.getObject("part_id",UUID.class),r.getLong("version"),r.getString("status"),r.getString("order_status"),r.getLong("missing"),
                r.getString("shape"),r.getBigDecimal("length_mm"),r.getBigDecimal("width_mm"),r.getBigDecimal("diameter_mm"))).optional().orElseThrow(RawService::missing);
    }
    private void touch(Preparation q) {
        db.sql("UPDATE preparation_requests SET version=version+1 WHERE id=:id").param("id",q.id()).update();
        db.sql("UPDATE manufacturing_orders SET version=version+1 WHERE id=:id").param("id",q.order()).update();
    }
    private static boolean same(BigDecimal a,BigDecimal b) { return a==null ? b==null : b!=null && a.compareTo(b)==0; }
    private void compatibility(Unit u,Preparation q) {
        rule(spec(u.partId()).active() && spec(q.part()).active(),"PART_INACTIVE");
        rule(q.length()!=null && u.shape().equals(q.shape()),"RAW_PROFILE_MISMATCH");
        if(u.stockUnit().equals("LENGTH")) rule(u.shape().equals("ROUND") ? same(u.diameterMm(),q.diameter()) : same(u.widthMm(),q.width()),"RAW_PROFILE_MISMATCH");
    }
    private void active(Preparation q,long version) {
        rule(q.version()==version,"STALE_VERSION"); rule(AssemblyService.active(q.orderStatus()),"STOCK_ORDER_NOT_OPEN");
        rule(q.status().equals("IN_PROGRESS") && q.missing()>0,"RAW_PREPARATION_NOT_ACTIVE");
    }
    @Transactional
    public Unit issue(UUID id,Issue p,UUID actor) {
        guard(); var u=unit(id); rule(u.version()==p.version(),"STALE_VERSION"); rule(u.status().equals("AVAILABLE"),"RAW_UNIT_NOT_AVAILABLE");
        var q=preparation(p.preparationId()); active(q,p.preparationVersion()); compatibility(u,q);
        db.sql("UPDATE raw_units SET status='ISSUED',preparation_id=:prep,version=version+1 WHERE id=:id").param("id",id).param("prep",q.id()).update();
        movement(u,"ISSUED",q.id(),actor,"",p.notes()); touch(q); audit(actor,"RAW_ISSUED",id); return unit(id);
    }
    @Transactional
    public Unit returnUnit(UUID id,ReturnInput p,UUID actor) {
        guard(); var u=unit(id); rule(u.version()==p.version(),"STALE_VERSION"); rule(u.status().equals("ISSUED"),"RAW_UNIT_NOT_ISSUED");
        var q=preparation(u.preparationId());
        db.sql("UPDATE raw_units SET status='AVAILABLE',preparation_id=NULL,version=version+1 WHERE id=:id").param("id",id).update();
        movement(u,"RETURNED",q.id(),actor,"",p.notes()); touch(q); audit(actor,"RAW_RETURNED",id); return unit(id);
    }
    private Plan plan(Unit u,Preparation q,CutInput p) {
        rule(u.version()==p.version(),"STALE_VERSION"); rule(u.status().equals("ISSUED"),"RAW_UNIT_NOT_ISSUED"); active(q,p.preparationVersion()); compatibility(u,q);
        rule(p.serials().size()<=q.missing(),"RAW_OUTPUT_EXCEEDS_SHORTAGE");
        var plan=RawCutPlanner.plan(u.stockUnit(),u.lengthMm(),u.widthMm(),q.length(),q.width(),p.serials().size(),p.columns(),p.rotated(),p.returnFirst(),p.returnSecond());
        rule(plan.scrapAmount().signum()==0 || !p.notes().isBlank(),"RAW_SCRAP_REASON_REQUIRED"); return plan;
    }
    public Plan preview(UUID id,CutInput p) { var u=unit(id); rule(u.preparationId()!=null,"RAW_UNIT_NOT_ISSUED"); return plan(u,preparation(u.preparationId()),p); }
    @Transactional
    public Plan cut(UUID id,CutInput p,UUID actor) {
        guard(); var u=unit(id); rule(u.preparationId()!=null,"RAW_UNIT_NOT_ISSUED"); var q=preparation(u.preparationId()); var plan=plan(u,q,p);
        // Receipt, output allocation and source consumption share one transaction; duplicate serials roll everything back.
        var outputs=stock.receive(new StockData.Receipt(q.part(),p.serials(),u.location(),u.code(),p.notes()),actor);
        stock.acceptCut(q.requirement(),outputs.stream().map(StockData.Piece::id).toList(),actor);
        UUID cut=UUID.randomUUID();
        db.sql("""
            INSERT INTO raw_cuts(id,source_id,preparation_id,output_length_mm,output_width_mm,columns_count,rotated,quantity,output_amount,returned_amount,scrap_amount,actor_id,notes)
            VALUES (:id,:source,:prep,:length,:width,:columns,:rotated,:quantity,:output,:returned,:scrap,:actor,:notes)
            """).param("id",cut).param("source",id).param("prep",q.id()).param("length",plan.outputLengthMm()).param("width",plan.outputWidthMm(),Types.NUMERIC)
            .param("columns",p.columns()).param("rotated",p.rotated()).param("quantity",plan.quantity()).param("output",plan.outputAmount()).param("returned",plan.returnedAmount())
            .param("scrap",plan.scrapAmount()).param("actor",actor).param("notes",p.notes().trim()).update();
        for(var output:outputs) db.sql("INSERT INTO raw_cut_outputs(cut_id,piece_id) VALUES (:cut,:piece)").param("cut",cut).param("piece",output.id()).update();
        db.sql("UPDATE raw_units SET status='CONSUMED',version=version+1 WHERE id=:id").param("id",id).update(); movement(u,"CONSUMED",q.id(),actor,"",p.notes());
        for(var r:plan.remainders()) {
            UUID child=insert("R-"+UUID.randomUUID().toString().toUpperCase(Locale.ROOT),u.partId(),r.lengthMm(),r.widthMm(),u.location(),r.returned()?"AVAILABLE":"SCRAPPED",id,r.returned()?null:q.id());
            movement(unit(child),r.returned()?"REMNANT_RETURNED":"SCRAPPED",q.id(),actor,u.code(),p.notes());
        }
        db.sql("UPDATE preparation_requests SET version=version+1 WHERE id=:id").param("id",q.id()).update();
        audit(actor,"RAW_CUT",cut); return plan;
    }
    public List<Balance> balances() {
        return db.sql("""
            SELECT p.id,p.code,p.name,p.stock_unit,
              coalesce(sum(CASE WHEN p.stock_unit='AREA' THEN u.length_mm*u.width_mm ELSE u.length_mm END) FILTER(WHERE u.status='AVAILABLE'),0) available,
              coalesce(sum(CASE WHEN p.stock_unit='AREA' THEN u.length_mm*u.width_mm ELSE u.length_mm END) FILTER(WHERE u.status='ISSUED'),0) issued,
              coalesce(sum(CASE WHEN p.stock_unit='AREA' THEN u.length_mm*u.width_mm ELSE u.length_mm END) FILTER(WHERE u.status='SCRAPPED'),0) scrap
            FROM parts p LEFT JOIN raw_units u ON u.part_id=p.id WHERE p.kind='MEASURED_STOCK' GROUP BY p.id ORDER BY p.code
            """).query((r,n)->new Balance(r.getObject("id",UUID.class),r.getString("code"),r.getString("name"),r.getString("stock_unit"),r.getBigDecimal("available"),r.getBigDecimal("issued"),r.getBigDecimal("scrap"))).list();
    }
    public List<Movement> movements() {
        return db.sql("""
            SELECT m.*,u.code,p.code part_code,p.stock_unit,o.code order_code,r.position_code,a.display_name actor_name
            FROM raw_movements m JOIN raw_units u ON u.id=m.unit_id JOIN parts p ON p.id=u.part_id JOIN users a ON a.id=m.actor_id
            LEFT JOIN preparation_requests q ON q.id=m.preparation_id LEFT JOIN order_requirements r ON r.id=q.requirement_id LEFT JOIN manufacturing_orders o ON o.id=r.order_id
            ORDER BY m.created_at DESC,m.id
            """).query((r,n)->new Movement(r.getObject("id",UUID.class),r.getString("code"),r.getString("part_code"),r.getString("stock_unit"),r.getString("action"),r.getBigDecimal("amount"),
                r.getString("order_code"),r.getString("position_code"),r.getString("actor_name"),r.getString("reference"),r.getString("notes"),r.getTimestamp("created_at").toInstant())).list();
    }
    public List<Cut> cuts() {
        var serials=new HashMap<UUID,List<String>>();
        db.sql("SELECT c.cut_id,s.serial_number FROM raw_cut_outputs c JOIN stock_pieces s ON s.id=c.piece_id ORDER BY s.serial_number")
            .query((r,n)->Map.entry(r.getObject("cut_id",UUID.class),r.getString("serial_number"))).list().forEach(e->serials.computeIfAbsent(e.getKey(),k->new ArrayList<>()).add(e.getValue()));
        return db.sql("""
            SELECT c.*,u.code source_code,p.stock_unit,o.code order_code,r.position_code,a.display_name actor_name
            FROM raw_cuts c JOIN raw_units u ON u.id=c.source_id JOIN parts p ON p.id=u.part_id JOIN preparation_requests q ON q.id=c.preparation_id
            JOIN order_requirements r ON r.id=q.requirement_id JOIN manufacturing_orders o ON o.id=r.order_id JOIN users a ON a.id=c.actor_id ORDER BY c.created_at DESC,c.id
            """).query((r,n)->new Cut(r.getObject("id",UUID.class),r.getString("source_code"),r.getString("stock_unit"),r.getString("order_code"),r.getString("position_code"),r.getInt("quantity"),
                r.getBigDecimal("output_amount"),r.getBigDecimal("returned_amount"),r.getBigDecimal("scrap_amount"),r.getBoolean("rotated"),r.getInt("columns_count"),r.getBigDecimal("output_length_mm"),r.getBigDecimal("output_width_mm"),
                r.getString("actor_name"),r.getString("notes"),r.getTimestamp("created_at").toInstant(),serials.getOrDefault(r.getObject("id",UUID.class),List.of()))).list();
    }
}

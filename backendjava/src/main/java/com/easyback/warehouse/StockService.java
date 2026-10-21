package com.easyback.warehouse;

import static com.easyback.warehouse.StockData.*;
import com.easyback.identity.ApiException;
import com.easyback.identity.IdentityStore;
import com.easyback.assembly.AssemblyService;
import com.easyback.notifications.NotificationWriter;
import java.sql.Types;
import java.util.*;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
public class StockService {
    private final JdbcClient db;
    private final IdentityStore identity;
    private final AssemblyService assembly;
    private final NotificationWriter notifications;
    public StockService(JdbcClient db, IdentityStore identity,AssemblyService assembly,NotificationWriter notifications) { this.db=db; this.identity=identity; this.assembly=assembly; this.notifications=notifications; }
    private static void rule(boolean valid,String code) { if (!valid) throw new ApiException(409,code,"Stock operation is not allowed."); }
    private static ApiException missing() { return new ApiException(404,"NOT_FOUND","Record not found."); }
    private void guard() {
        db.sql("SELECT id FROM security_guard WHERE id=1 FOR UPDATE").query(Integer.class).single();
        db.sql("SELECT id FROM catalog_guard WHERE id=1 FOR UPDATE").query(Integer.class).single();
    }
    private void audit(UUID actor,String action,UUID target) {
        db.sql("INSERT INTO audit_logs(id,actor_id,action,target_id) VALUES (:id,:actor,:action,:target)")
            .param("id",UUID.randomUUID()).param("actor",actor).param("action",action).param("target",target).update();
    }
    private static final String PIECES="""
        SELECT s.*,p.code part_code,p.name part_name,r.order_id,r.position_code,o.code order_code,u.display_name recipient_name,
          (SELECT d.id FROM defect_cases d WHERE d.piece_id=s.id AND d.status NOT IN ('RETURNED','SCRAPPED')) defect_id
        FROM stock_pieces s JOIN parts p ON p.id=s.part_id LEFT JOIN order_requirements r ON r.id=s.requirement_id
        LEFT JOIN manufacturing_orders o ON o.id=r.order_id LEFT JOIN users u ON u.id=s.recipient_id
        """;
    private static final RowMapper<Piece> PIECE=(r,n) -> new Piece(r.getObject("id",UUID.class),r.getString("serial_number"),r.getObject("part_id",UUID.class),
        r.getString("part_code"),r.getString("part_name"),r.getString("location"),r.getString("status"),r.getLong("version"),
        r.getObject("requirement_id",UUID.class),r.getObject("order_id",UUID.class),r.getString("order_code"),r.getString("position_code"),r.getString("recipient_name"),r.getObject("task_id",UUID.class),r.getObject("defect_id",UUID.class));
    public List<Piece> pieces() { return db.sql(PIECES+" ORDER BY s.received_at DESC,s.serial_number").query(PIECE).list(); }
    private Piece piece(UUID id) { return db.sql(PIECES+" WHERE s.id=:id").param("id",id).query(PIECE).optional().orElseThrow(StockService::missing); }
    public List<Balance> balances() {
        return db.sql("""
            SELECT p.id,p.code,p.name,count(s.id) FILTER(WHERE s.status='AVAILABLE') available,
              count(s.id) FILTER(WHERE s.status='RESERVED') reserved,count(s.id) FILTER(WHERE s.status='ISSUED') issued,
              count(s.id) FILTER(WHERE s.status='QUARANTINED') quarantined,count(s.id) FILTER(WHERE s.status='INSTALLED') installed,
              count(s.id) FILTER(WHERE s.status='REJECTED') rejected,count(s.id) FILTER(WHERE s.status='IN_REPAIR') in_repair,
              count(s.id) FILTER(WHERE s.status='REPAIRED') repaired,count(s.id) FILTER(WHERE s.status='SCRAPPED') scrapped
            FROM parts p LEFT JOIN stock_pieces s ON s.part_id=p.id WHERE p.kind='SERIALIZED_PIECE'
            GROUP BY p.id ORDER BY p.code
            """).query((r,n) -> new Balance(r.getObject("id",UUID.class),r.getString("code"),r.getString("name"),
                r.getLong("available"),r.getLong("reserved"),r.getLong("issued"),r.getLong("quarantined"),r.getLong("installed"),
                r.getLong("rejected"),r.getLong("in_repair"),r.getLong("repaired"),r.getLong("scrapped"))).list();
    }
    private record Head(long version,String status,UUID responsible) {}
    private Head head(UUID order) {
        return db.sql("SELECT version,status,responsible_id FROM manufacturing_orders WHERE id=:id").param("id",order)
            .query((r,n) -> new Head(r.getLong("version"),r.getString("status"),r.getObject("responsible_id",UUID.class))).optional().orElseThrow(StockService::missing);
    }
    private Head editable(UUID order,long version) {
        var h=head(order); rule(h.version()==version,"STALE_VERSION"); rule(AssemblyService.active(h.status()),"STOCK_ORDER_NOT_OPEN"); return h;
    }
    private void touch(UUID order) { db.sql("UPDATE manufacturing_orders SET version=version+1 WHERE id=:id").param("id",order).update(); }
    private List<Coverage> coverage(UUID order) {
        return db.sql("""
            SELECT r.*,count(s.id) FILTER(WHERE s.status='RESERVED') reserved,count(s.id) FILTER(WHERE s.status='ISSUED') issued,
              greatest(0,r.quantity-count(s.id) FILTER(WHERE s.status<>'REJECTED')) missing,q.id preparation_id,q.status preparation_status,count(s.id) FILTER(WHERE s.status='INSTALLED') installed
            FROM order_requirements r LEFT JOIN stock_pieces s ON s.requirement_id=r.id
            LEFT JOIN preparation_requests q ON q.requirement_id=r.id WHERE r.order_id=:id
            GROUP BY r.id,q.id ORDER BY r.position_code
            """).param("id",order).query((r,n) -> new Coverage(r.getObject("id",UUID.class),r.getString("position_code"),r.getString("part_code"),
                r.getString("part_name"),r.getInt("quantity"),r.getLong("reserved"),r.getLong("issued"),r.getLong("missing"),
                r.getObject("preparation_id",UUID.class),r.getString("preparation_status"),r.getLong("installed"))).list();
    }
    public OrderStock orderStock(UUID order,UUID actor,boolean all) {
        var h=head(order); if (!all && !h.responsible().equals(actor)) throw missing();
        return new OrderStock(order,h.version(),h.status(),coverage(order),db.sql(PIECES+" WHERE r.order_id=:id ORDER BY r.position_code,s.serial_number").param("id",order).query(PIECE).list());
    }
    private void movement(UUID piece,String action,UUID requirement,UUID recipient,UUID actor,String reference,String notes) {
        db.sql("INSERT INTO stock_movements(id,piece_id,action,requirement_id,recipient_id,actor_id,reference,notes) VALUES (:id,:piece,:action,:req,:recipient,:actor,:reference,:notes)")
            .param("id",UUID.randomUUID()).param("piece",piece).param("action",action).param("req",requirement,Types.OTHER)
            .param("recipient",recipient,Types.OTHER).param("actor",actor).param("reference",reference.trim()).param("notes",notes.trim()).update();
    }
    @Transactional
    public List<Piece> receive(Receipt p,UUID actor) {
        guard(); var part=db.sql("SELECT kind,active FROM parts WHERE id=:id").param("id",p.partId()).query((r,n) -> Map.entry(r.getString("kind"),r.getBoolean("active"))).optional().orElseThrow(StockService::missing);
        rule(part.getValue(),"PART_INACTIVE"); rule(part.getKey().equals("SERIALIZED_PIECE"),"STOCK_SERIALIZED_ONLY");
        var serials=p.serials().stream().map(s -> s.toUpperCase(Locale.ROOT)).toList();
        rule(new HashSet<>(serials).size()==serials.size(),"STOCK_DUPLICATE_SERIAL");
        List<Piece> result=new ArrayList<>();
        for (String serial:serials) {
            rule(db.sql("SELECT count(*) FROM stock_pieces WHERE serial_number=:serial").param("serial",serial).query(Long.class).single()==0,"STOCK_DUPLICATE_SERIAL");
            UUID id=UUID.randomUUID();
            db.sql("INSERT INTO stock_pieces(id,serial_number,part_id,location,status) VALUES (:id,:serial,:part,:location,'AVAILABLE')")
                .param("id",id).param("serial",serial).param("part",p.partId()).param("location",p.location().trim()).update();
            movement(id,"RECEIVED",null,null,actor,p.reference(),p.notes()); result.add(piece(id));
        }
        audit(actor,"STOCK_RECEIVED",p.partId()); return result;
    }
    private void syncPreparation(UUID order,UUID actor) {
        for (var r:coverage(order)) if (r.preparationId()!=null) {
            String next=r.missing()==0 ? "FULFILLED" : r.preparationStatus().equals("FULFILLED") ? "REQUESTED" : r.preparationStatus();
            if (!next.equals(r.preparationStatus())) {
                db.sql("UPDATE preparation_requests SET status=:status,version=version+1 WHERE id=:id")
                .param("id",r.preparationId()).param("status",next).update();
                notifications.preparation(order,r.preparationId(),next,actor);
            }
        }
    }
    @Transactional
    public void allocateOnRelease(UUID order,UUID actor) { guard(); rule(head(order).status().equals("OPEN"),"STOCK_ORDER_NOT_OPEN"); allocate(order,actor); }
    private void allocate(UUID order,UUID actor) {
        for (var r:coverage(order)) {
            var ids=db.sql("""
                SELECT s.id FROM stock_pieces s JOIN parts p ON p.id=s.part_id JOIN order_requirements r ON r.part_id=p.id
                WHERE r.id=:req AND s.status='AVAILABLE' AND p.active ORDER BY s.received_at,s.id LIMIT :amount
                """).param("req",r.requirementId()).param("amount",r.missing()).query(UUID.class).list();
            for (UUID id:ids) {
                db.sql("UPDATE stock_pieces SET status='RESERVED',requirement_id=:req,version=version+1 WHERE id=:id")
                    .param("id",id).param("req",r.requirementId()).update();
                movement(id,"RESERVED",r.requirementId(),null,actor,"","");
            }
        }
        syncPreparation(order,actor);
    }
    @Transactional
    public OrderStock reserve(UUID order,long version,UUID actor) {
        guard(); editable(order,version); allocate(order,actor); touch(order); audit(actor,"STOCK_RESERVED",order); return orderStock(order,actor,true);
    }
    @Transactional
    public OrderStock issue(UUID order,Selection p,UUID actor) {
        guard(); var h=editable(order,p.version());
        rule(new HashSet<>(p.pieceIds()).size()==p.pieceIds().size(),"STOCK_SELECTION_INVALID");
        UUID recipient=p.recipientId()==null?h.responsible():p.recipientId(); var perms=identity.permissions(recipient);
        rule(db.sql("SELECT count(*) FROM users WHERE id=:id AND active=true").param("id",recipient).query(Long.class).single()==1
            && (recipient.equals(h.responsible()) && (perms.contains("orders.view") || perms.contains("orders.view-assigned")) || assembly.eligible(recipient)),"TASK_WORKER_INVALID");
        for (UUID id:p.pieceIds()) {
            var piece=piece(id); rule(order.equals(piece.orderId()) && piece.status().equals("RESERVED"),"STOCK_SELECTION_INVALID");
            rule(db.sql("SELECT active FROM parts WHERE id=:id").param("id",piece.partId()).query(Boolean.class).single(),"PART_INACTIVE");
            UUID task=assembly.issued(piece.requirementId(),recipient,id,actor);
            rule(task!=null || recipient.equals(h.responsible()),"TASK_RECIPIENT_MISMATCH");
            db.sql("UPDATE stock_pieces SET status='ISSUED',recipient_id=:recipient,task_id=:task,version=version+1 WHERE id=:id")
                .param("id",id).param("recipient",recipient).param("task",task,Types.OTHER).update();
            movement(id,"ISSUED",piece.requirementId(),recipient,actor,"",p.notes());
        }
        touch(order); audit(actor,"STOCK_ISSUED",order); return orderStock(order,actor,true);
    }
    @Transactional
    public OrderStock returnPiece(UUID order,UUID id,ReturnInput p,UUID actor) {
        guard(); editable(order,p.version()); var piece=piece(id);
        rule(order.equals(piece.orderId()) && piece.status().equals("ISSUED"),"STOCK_SELECTION_INVALID");
        String status=p.condition()==Condition.GOOD ? "AVAILABLE" : "QUARANTINED";
        assembly.returned(piece.taskId(),id,actor,p.notes());
        db.sql("UPDATE stock_pieces SET status=:status,requirement_id=NULL,recipient_id=NULL,task_id=NULL,version=version+1 WHERE id=:id")
            .param("id",id).param("status",status).update();
        movement(id,p.condition()==Condition.GOOD ? "RETURNED_GOOD" : "RETURNED_DEFECTIVE",piece.requirementId(),null,actor,"",p.notes());
        syncPreparation(order,actor); touch(order); audit(actor,"STOCK_RETURNED",order); return orderStock(order,actor,true);
    }
    @Transactional
    public Piece restore(UUID id,RestoreInput p,UUID actor) {
        guard(); var piece=piece(id); rule(piece.version()==p.version(),"STALE_VERSION"); rule(piece.status().equals("QUARANTINED"),"STOCK_SELECTION_INVALID");
        rule(db.sql("SELECT count(*) FROM defect_cases WHERE piece_id=:id AND status NOT IN ('RETURNED','SCRAPPED')").param("id",id).query(Long.class).single()==0,"DEFECT_USE_WORKFLOW");
        rule(db.sql("SELECT active FROM parts WHERE id=:id").param("id",piece.partId()).query(Boolean.class).single(),"PART_INACTIVE");
        db.sql("UPDATE stock_pieces SET status='AVAILABLE',version=version+1 WHERE id=:id").param("id",id).update();
        movement(id,"RESTORED",null,null,actor,"",p.notes()); audit(actor,"STOCK_RESTORED",id); return piece(id);
    }
    @Transactional
    public void beforeCancel(UUID order,UUID actor) {
        guard(); var pieces=db.sql(PIECES+" WHERE r.order_id=:id").param("id",order).query(PIECE).list();
        rule(db.sql("SELECT count(*) FROM raw_units u JOIN preparation_requests q ON q.id=u.preparation_id JOIN order_requirements r ON r.id=q.requirement_id WHERE r.order_id=:id AND u.status='ISSUED'")
            .param("id",order).query(Long.class).single()==0,"RAW_RETURN_BEFORE_CANCEL");
        rule(pieces.stream().noneMatch(p -> p.status().equals("INSTALLED")),"TASK_UNINSTALL_BEFORE_CANCEL");
        rule(pieces.stream().noneMatch(p -> p.status().equals("REJECTED")),"DEFECT_RETURN_BEFORE_CANCEL");
        rule(pieces.stream().noneMatch(p -> p.status().equals("ISSUED")),"STOCK_RETURN_BEFORE_CANCEL");
        for (var piece:pieces) {
            db.sql("UPDATE stock_pieces SET status='AVAILABLE',requirement_id=NULL,version=version+1 WHERE id=:id").param("id",piece.id()).update();
            movement(piece.id(),"UNRESERVED",piece.requirementId(),null,actor,"","Order cancelled");
        }
        db.sql("UPDATE preparation_requests SET status='CANCELLED',version=version+1 WHERE requirement_id IN (SELECT id FROM order_requirements WHERE order_id=:id) AND status IN ('REQUESTED','IN_PROGRESS')")
            .param("id",order).update();
    }
    @Transactional
    public OrderStock requestPreparation(UUID order,PreparationInput p,UUID actor) {
        guard(); editable(order,p.version());
        var r=coverage(order).stream().filter(c -> c.requirementId().equals(p.requirementId())).findFirst().orElseThrow(StockService::missing);
        rule(r.missing()>0,"STOCK_NO_SHORTAGE"); rule(r.preparationId()==null || r.preparationStatus().equals("FULFILLED"),"PREPARATION_EXISTS");
        if (r.preparationId()==null) db.sql("INSERT INTO preparation_requests(id,requirement_id,requested_quantity,status,notes,requested_by) VALUES (:id,:req,:qty,'REQUESTED',:notes,:actor)")
            .param("id",UUID.randomUUID()).param("req",p.requirementId()).param("qty",r.missing()).param("notes",p.notes().trim()).param("actor",actor).update();
        else db.sql("UPDATE preparation_requests SET requested_quantity=:qty,status='REQUESTED',notes=:notes,requested_by=:actor,requested_at=CURRENT_TIMESTAMP,version=version+1 WHERE id=:id")
            .param("id",r.preparationId()).param("qty",r.missing()).param("notes",p.notes().trim()).param("actor",actor).update();
        UUID prep=db.sql("SELECT id FROM preparation_requests WHERE requirement_id=:id").param("id",p.requirementId()).query(UUID.class).single();
        notifications.preparation(order,prep,"REQUESTED",actor);
        touch(order); audit(actor,"PREPARATION_REQUESTED",order); return orderStock(order,actor,true);
    }
    public List<Preparation> preparations() {
        return db.sql("""
            SELECT q.*,r.order_id,r.position_code,r.part_code,r.part_name,o.code order_code,o.title order_title,
              greatest(0,r.quantity-(SELECT count(*) FROM stock_pieces s WHERE s.requirement_id=r.id AND s.status<>'REJECTED')) missing
            FROM preparation_requests q JOIN order_requirements r ON r.id=q.requirement_id JOIN manufacturing_orders o ON o.id=r.order_id
            ORDER BY q.requested_at DESC,q.id
            """).query((r,n) -> new Preparation(r.getObject("id",UUID.class),r.getObject("order_id",UUID.class),r.getString("order_code"),r.getString("order_title"),
                r.getString("position_code"),r.getString("part_code"),r.getString("part_name"),r.getInt("requested_quantity"),r.getLong("missing"),
                r.getString("status"),r.getString("notes"),r.getLong("version"),r.getTimestamp("requested_at").toInstant())).list();
    }
    @Transactional
    public List<Preparation> startPreparation(UUID id,long version,UUID actor) {
        guard(); var p=preparations().stream().filter(q -> q.id().equals(id)).findFirst().orElseThrow(StockService::missing);
        rule(p.version()==version,"STALE_VERSION"); rule(AssemblyService.active(head(p.orderId()).status()),"STOCK_ORDER_NOT_OPEN");
        rule(p.status().equals("REQUESTED") && p.missing()>0,"STOCK_NO_SHORTAGE");
        db.sql("UPDATE preparation_requests SET status='IN_PROGRESS',version=version+1 WHERE id=:id").param("id",id).update();
        touch(p.orderId()); audit(actor,"PREPARATION_STARTED",id); return preparations();
    }
    public List<Movement> movements() {
        return db.sql("""
            SELECT m.*,s.serial_number,r.position_code,o.code order_code,u.display_name actor_name,v.display_name recipient_name
            FROM stock_movements m JOIN stock_pieces s ON s.id=m.piece_id JOIN users u ON u.id=m.actor_id
            LEFT JOIN order_requirements r ON r.id=m.requirement_id LEFT JOIN manufacturing_orders o ON o.id=r.order_id
            LEFT JOIN users v ON v.id=m.recipient_id ORDER BY m.created_at DESC,m.id
            """).query((r,n) -> new Movement(r.getObject("id",UUID.class),r.getString("serial_number"),r.getString("action"),r.getString("order_code"),
                r.getString("position_code"),r.getString("recipient_name"),r.getString("actor_name"),r.getString("reference"),r.getString("notes"),r.getTimestamp("created_at").toInstant())).list();
    }
    @Transactional
    public void defectReceived(UUID order,UUID actor) { guard(); syncPreparation(order,actor); touch(order); }
    @Transactional
    public void acceptCut(UUID requirement,List<UUID> pieces,UUID actor) {
        guard();
        for (UUID id:pieces) {
            db.sql("UPDATE stock_pieces SET status='RESERVED',requirement_id=:req,version=version+1 WHERE id=:id")
                .param("req",requirement).param("id",id).update();
            movement(id,"RESERVED",requirement,null,actor,"","Cut output allocated to its preparation request");
        }
        UUID order=db.sql("SELECT order_id FROM order_requirements WHERE id=:id").param("id",requirement).query(UUID.class).single();
        syncPreparation(order,actor); touch(order);
    }
}

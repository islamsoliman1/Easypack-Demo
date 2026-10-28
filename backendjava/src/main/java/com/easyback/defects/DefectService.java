package com.easyback.defects;

import static com.easyback.defects.DefectData.*;
import com.easyback.assembly.AssemblyData.Removal;
import com.easyback.assembly.AssemblyService;
import com.easyback.warehouse.StockService;
import com.easyback.identity.ApiException;
import com.easyback.notifications.NotificationWriter;
import java.util.*;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class DefectService {
    private final JdbcClient db;
    private final AssemblyService assembly;
    private final StockService stock;
    private final NotificationWriter notifications;
    public DefectService(JdbcClient db,AssemblyService assembly,StockService stock,NotificationWriter notifications) { this.db=db; this.assembly=assembly; this.stock=stock; this.notifications=notifications; }
    private static void rule(boolean valid,String code) { if(!valid) throw new ApiException(409,code,"Defect operation is not allowed."); }
    private static ApiException missing() { return new ApiException(404,"NOT_FOUND","Defect not found."); }
    private void guard() {
        db.sql("SELECT id FROM security_guard WHERE id=1 FOR UPDATE").query(Integer.class).single();
        db.sql("SELECT id FROM catalog_guard WHERE id=1 FOR UPDATE").query(Integer.class).single();
    }
    private static final String CASES="""
        SELECT d.*,r.order_id,r.position_code,t.occurrence,r.part_id,r.part_code,r.part_name,o.code order_code,o.machine_id,o.machine_serial,
          s.serial_number,s.status piece_status,u.display_name reporter_name
        FROM defect_cases d JOIN assembly_tasks t ON t.id=d.task_id JOIN order_requirements r ON r.id=t.requirement_id
        JOIN manufacturing_orders o ON o.id=r.order_id JOIN stock_pieces s ON s.id=d.piece_id JOIN users u ON u.id=d.reported_by
        """;
    private static final RowMapper<Case> CASE=(r,n)->new Case(r.getObject("id",UUID.class),r.getObject("task_id",UUID.class),r.getObject("piece_id",UUID.class),
        r.getObject("order_id",UUID.class),r.getString("order_code"),r.getObject("machine_id",UUID.class),r.getString("machine_serial"),r.getString("position_code"),r.getInt("occurrence"),
        r.getString("serial_number"),r.getObject("part_id",UUID.class),r.getString("part_code"),r.getString("part_name"),r.getObject("reported_by",UUID.class),r.getString("reporter_name"),
        r.getString("reason"),r.getString("status"),r.getString("piece_status"),r.getLong("version"),r.getTimestamp("reported_at").toInstant());
    private Case load(UUID id) { return db.sql(CASES+" WHERE d.id=:id").param("id",id).query(CASE).optional().orElseThrow(DefectService::missing); }
    public List<Case> list(UUID actor,boolean all) { return db.sql(CASES+" WHERE :all OR d.reported_by=:actor ORDER BY d.reported_at DESC,d.id")
        .param("all",all).param("actor",actor).query(CASE).list(); }
    public Detail detail(UUID id,UUID actor,boolean all) {
        var d=load(id); if(!all && !actor.equals(d.reportedBy())) throw missing();
        var events=db.sql("SELECT e.*,u.display_name FROM defect_events e JOIN users u ON u.id=e.actor_id WHERE e.defect_id=:id ORDER BY e.created_at,e.id")
            .param("id",id).query((r,n)->new Event(r.getObject("id",UUID.class),r.getString("action"),r.getString("display_name"),r.getString("notes"),r.getTimestamp("created_at").toInstant())).list();
        return new Detail(d,events);
    }
    private void event(UUID id,String action,UUID actor,String notes) {
        db.sql("INSERT INTO defect_events(id,defect_id,action,actor_id,notes) VALUES (:id,:defect,:action,:actor,:notes)")
            .param("id",UUID.randomUUID()).param("defect",id).param("action",action).param("actor",actor).param("notes",notes.trim()).update();
        db.sql("INSERT INTO audit_logs(id,actor_id,action,target_id) VALUES (:id,:actor,:action,:target)")
            .param("id",UUID.randomUUID()).param("actor",actor).param("action","DEFECT_"+action).param("target",id).update();
    }
    @Transactional
    public Detail report(UUID task,Removal p,UUID actor,boolean all) {
        guard(); var t=assembly.reject(task,p,actor,all).task(); UUID id=UUID.randomUUID();
        db.sql("INSERT INTO defect_cases(id,task_id,piece_id,reported_by,reason) VALUES (:id,:task,:piece,:actor,:reason)")
            .param("id",id).param("task",task).param("piece",t.pieceId()).param("actor",actor).param("reason",p.reason().trim()).update();
        event(id,"REPORTED",actor,p.reason()); stock.defectReceived(t.orderId(),actor);
        notifications.emit(NotificationWriter.Kind.DEFECT_REPORTED,t.orderId(),id,"reported",actor); return detail(id,actor,true);
    }
    private Case editable(UUID id,long version) { var d=load(id); rule(d.version()==version,"STALE_VERSION"); return d; }
    private void transition(Case d,String state,String pieceState,String action,String movement,UUID actor,String notes,String location) {
        db.sql("UPDATE defect_cases SET status=:status,version=version+1 WHERE id=:id").param("status",state).param("id",d.id()).update();
        db.sql("UPDATE stock_pieces SET status=:status,location=coalesce(:location,location),version=version+1 WHERE id=:id")
            .param("id",d.pieceId()).param("status",pieceState).param("location",location,java.sql.Types.VARCHAR).update();
        db.sql("INSERT INTO stock_movements(id,piece_id,action,requirement_id,actor_id,reference,notes) SELECT :id,:piece,:action,t.requirement_id,:actor,'',:notes FROM assembly_tasks t WHERE t.id=:task")
            .param("id",UUID.randomUUID()).param("piece",d.pieceId()).param("action",movement).param("actor",actor).param("notes",notes.trim()).param("task",d.taskId()).update();
        event(d.id(),action,actor,notes);
    }
    @Transactional
    public Detail receive(UUID id,Receipt p,UUID actor) {
        guard(); var d=editable(id,p.version()); rule(d.status().equals("REPORTED") && d.pieceStatus().equals("REJECTED"),"DEFECT_STATE_INVALID");
        assembly.returned(d.taskId(),d.pieceId(),actor,p.notes());
        db.sql("UPDATE stock_pieces SET requirement_id=NULL,recipient_id=NULL,task_id=NULL,status='QUARANTINED' WHERE id=:id").param("id",d.pieceId()).update();
        transition(d,"QUARANTINED","QUARANTINED","RECEIVED","DEFECT_RECEIVED",actor,p.notes(),p.location().trim());
        stock.defectReceived(d.orderId(),actor);
        notifications.emit(NotificationWriter.Kind.DEFECT_RECEIVED,d.orderId(),id,Long.toString(d.version()+1),actor); return detail(id,actor,true);
    }
    @Transactional
    public Detail start(UUID id,Action p,UUID actor) {
        guard(); var d=editable(id,p.version()); rule(d.status().equals("QUARANTINED") && d.pieceStatus().equals("QUARANTINED"),"DEFECT_STATE_INVALID");
        transition(d,"IN_REPAIR","IN_REPAIR","REPAIR_STARTED","REPAIR_STARTED",actor,p.notes(),null); return detail(id,actor,true);
    }
    @Transactional
    public Detail finish(UUID id,Action p,UUID actor) {
        guard(); var d=editable(id,p.version()); rule(d.status().equals("IN_REPAIR") && d.pieceStatus().equals("IN_REPAIR"),"DEFECT_STATE_INVALID");
        transition(d,"REPAIRED","REPAIRED","REPAIR_COMPLETED","REPAIR_COMPLETED",actor,p.notes(),null);
        notifications.emit(NotificationWriter.Kind.REPAIR_COMPLETED,d.orderId(),id,Long.toString(d.version()+1),actor); return detail(id,actor,true);
    }
    @Transactional
    public Detail accept(UUID id,Receipt p,UUID actor) {
        guard(); var d=editable(id,p.version()); rule(d.status().equals("REPAIRED") && d.pieceStatus().equals("REPAIRED"),"DEFECT_STATE_INVALID");
        rule(db.sql("SELECT p.active FROM parts p JOIN stock_pieces s ON s.part_id=p.id WHERE s.id=:id").param("id",d.pieceId()).query(Boolean.class).single(),"PART_INACTIVE");
        transition(d,"RETURNED","AVAILABLE","ACCEPTED","REPAIR_RECEIVED",actor,p.notes(),p.location().trim()); return detail(id,actor,true);
    }
    @Transactional
    public Detail scrap(UUID id,Action p,UUID actor) {
        guard(); var d=editable(id,p.version()); rule(Set.of("QUARANTINED","IN_REPAIR","REPAIRED").contains(d.status()) && d.status().equals(d.pieceStatus()),"DEFECT_STATE_INVALID");
        transition(d,"SCRAPPED","SCRAPPED","SCRAPPED","SCRAPPED",actor,p.notes(),null); return detail(id,actor,true);
    }
}

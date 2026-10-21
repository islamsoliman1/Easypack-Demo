package com.easyback.assembly;

import static com.easyback.assembly.AssemblyData.*;
import com.easyback.identity.ApiException;
import com.easyback.identity.IdentityStore;
import com.easyback.operations.OperationsService;
import com.easyback.time.WorkService;
import com.easyback.notifications.NotificationWriter;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class AssemblyService {
    private final JdbcClient db;
    private final IdentityStore identity;
    private final OperationsService operations;
    private final WorkService work;
    private final NotificationWriter notifications;
    public AssemblyService(JdbcClient db,IdentityStore identity,OperationsService operations,WorkService work,NotificationWriter notifications) { this.db=db; this.identity=identity; this.operations=operations; this.work=work; this.notifications=notifications; }
    public static boolean active(String status) { return Set.of("OPEN","IN_PROGRESS","AWAITING_TESTING","TESTING","REWORK").contains(status); }
    private static void rule(boolean valid,String code) { if(!valid) throw new ApiException(409,code,"Assembly operation is not allowed."); }
    private static ApiException missing() { return new ApiException(404,"NOT_FOUND","Task not found."); }
    private void guard() {
        db.sql("SELECT id FROM security_guard WHERE id=1 FOR UPDATE").query(Integer.class).single();
        db.sql("SELECT id FROM catalog_guard WHERE id=1 FOR UPDATE").query(Integer.class).single();
    }
    private static Instant time(ResultSet r,String field) throws SQLException { var t=r.getTimestamp(field); return t==null?null:t.toInstant(); }
    private static final String TASKS="""
        SELECT t.*,r.order_id,r.position_code,r.part_id,r.part_code,r.part_name,r.expected_install_minutes,r.notes requirement_notes,
          o.code order_code,o.title order_title,o.status order_status,o.machine_id,o.machine_serial,o.machine_name,o.delivery_at,
          u.display_name worker_name,s.id piece_id,s.serial_number,s.status piece_status,i.id installation_id,i.installed_at,
          EXISTS(SELECT 1 FROM work_sessions w JOIN work_days d ON d.day=w.day WHERE w.task_id=t.id AND w.ended_at IS NULL
            AND :worknow < ((d.day + CASE WHEN d.extended THEN TIME '20:00' ELSE TIME '17:00' END) AT TIME ZONE 'Africa/Cairo')) work_running
        FROM assembly_tasks t JOIN order_requirements r ON r.id=t.requirement_id JOIN manufacturing_orders o ON o.id=r.order_id
        LEFT JOIN users u ON u.id=t.worker_id LEFT JOIN stock_pieces s ON s.task_id=t.id
        LEFT JOIN installations i ON i.task_id=t.id AND i.removed_at IS NULL
        """;
    private static final RowMapper<Task> TASK=(r,n)->new Task(r.getObject("id",UUID.class),r.getObject("requirement_id",UUID.class),r.getObject("order_id",UUID.class),
        r.getString("order_code"),r.getString("order_title"),r.getString("order_status"),r.getObject("machine_id",UUID.class),r.getString("machine_serial"),r.getString("machine_name"),time(r,"delivery_at"),
        r.getString("position_code"),r.getInt("occurrence"),r.getString("part_code"),r.getString("part_name"),r.getBigDecimal("expected_install_minutes"),r.getString("requirement_notes"),
        r.getObject("worker_id",UUID.class),r.getString("worker_name"),r.getString("status"),r.getLong("version"),time(r,"started_at"),r.getObject("piece_id",UUID.class),r.getString("serial_number"),
        r.getString("piece_status"),r.getObject("installation_id",UUID.class),time(r,"installed_at"),r.getBoolean("work_running"),r.getObject("part_id",UUID.class));
    private Task task(UUID id) { return db.sql(TASKS+" WHERE t.id=:id").param("id",id).param("worknow",Timestamp.from(work.now())).query(TASK).optional().orElseThrow(AssemblyService::missing); }
    private Task scoped(UUID id,UUID actor,boolean all) { var t=task(id); if(!all && !actor.equals(t.workerId())) throw missing(); return t; }
    public List<Task> tasks(UUID actor,boolean all,UUID order) {
        return db.sql(TASKS+" WHERE (:all OR t.worker_id=:actor) AND (CAST(:order AS UUID) IS NULL OR r.order_id=:order) ORDER BY o.created_at DESC,o.code,r.position_code,t.occurrence")
            .param("all",all).param("actor",actor).param("order",order,Types.OTHER).param("worknow",Timestamp.from(work.now())).query(TASK).list();
    }
    public Detail detail(UUID id,UUID actor,boolean all) {
        var t=scoped(id,actor,all);
        var events=db.sql("""
            SELECT e.*,u.display_name worker_name,a.display_name actor_name,s.serial_number
            FROM assembly_events e JOIN users a ON a.id=e.actor_id LEFT JOIN users u ON u.id=e.worker_id LEFT JOIN stock_pieces s ON s.id=e.piece_id
            WHERE e.task_id=:id ORDER BY e.created_at,e.id
            """).param("id",id).query((r,n)->new Event(r.getObject("id",UUID.class),r.getString("action"),r.getString("worker_name"),r.getString("actor_name"),r.getString("serial_number"),r.getString("notes"),time(r,"created_at"))).list();
        return new Detail(t,events,work.detail(id));
    }
    public boolean eligible(UUID worker) {
        var p=identity.permissions(worker);
        return db.sql("SELECT count(*) FROM users WHERE id=:id AND active").param("id",worker).query(Long.class).single()==1
            && p.contains("tasks.work") && (p.contains("tasks.view-assigned") || p.contains("tasks.view") && p.contains("orders.view"));
    }
    public List<Person> workers() {
        return db.sql("SELECT id,display_name,email FROM users WHERE active ORDER BY display_name,email")
            .query((r,n)->new Person(r.getObject("id",UUID.class),r.getString("display_name"),r.getString("email"))).list().stream().filter(p->eligible(p.id())).toList();
    }
    public List<Person> recipients(UUID order) {
        var ids=db.sql("SELECT responsible_id FROM manufacturing_orders WHERE id=:id UNION SELECT t.worker_id FROM assembly_tasks t JOIN order_requirements r ON r.id=t.requirement_id WHERE r.order_id=:id AND t.worker_id IS NOT NULL")
            .param("id",order).query(UUID.class).list();
        return db.sql("SELECT id,display_name,email FROM users WHERE active ORDER BY display_name,email").query((r,n)->new Person(r.getObject("id",UUID.class),r.getString("display_name"),r.getString("email")))
            .list().stream().filter(p->ids.contains(p.id())).toList();
    }
    private void event(Task t,String action,UUID actor,UUID worker,UUID piece,String notes) {
        db.sql("INSERT INTO assembly_events(id,task_id,action,actor_id,worker_id,piece_id,notes) VALUES (:id,:task,:action,:actor,:worker,:piece,:notes)")
            .param("id",UUID.randomUUID()).param("task",t.id()).param("action",action).param("actor",actor).param("worker",worker,Types.OTHER).param("piece",piece,Types.OTHER).param("notes",notes.trim()).update();
        db.sql("INSERT INTO audit_logs(id,actor_id,action,target_id) VALUES (:id,:actor,:action,:task)")
            .param("id",UUID.randomUUID()).param("actor",actor).param("action","TASK_"+action).param("task",t.id()).update();
    }
    private void syncOrder(UUID order,UUID actor) {
        String before=db.sql("SELECT status FROM manufacturing_orders WHERE id=:id").param("id",order).query(String.class).single();
        db.sql("""
            UPDATE manufacturing_orders o SET status=CASE WHEN o.rework_required THEN 'REWORK' ELSE x.status END,assembly_completed_at=CASE WHEN x.status='AWAITING_TESTING'
              THEN coalesce(o.assembly_completed_at,CURRENT_TIMESTAMP) ELSE NULL END,version=o.version+1
            FROM (SELECT CASE WHEN count(*)>0 AND bool_and(t.status='INSTALLED') THEN 'AWAITING_TESTING'
                WHEN count(*) FILTER(WHERE t.status<>'PENDING')>0 THEN 'IN_PROGRESS' ELSE 'OPEN' END status
              FROM assembly_tasks t JOIN order_requirements r ON r.id=t.requirement_id WHERE r.order_id=:id) x
            WHERE o.id=:id AND o.status IN ('OPEN','IN_PROGRESS','AWAITING_TESTING','REWORK')
            """).param("id",order).update();
        notifications.ready(order,before,actor);
    }
    private void editable(Task t,long version) { rule(t.version()==version,"STALE_VERSION"); rule(active(t.orderStatus()),"ORDER_LOCKED"); }
    @Transactional
    public void createTasks(UUID order) {
        guard(); db.sql("INSERT INTO assembly_tasks(id,requirement_id,occurrence) SELECT gen_random_uuid(),r.id,n FROM order_requirements r CROSS JOIN LATERAL generate_series(1,r.quantity) n WHERE r.order_id=:id ON CONFLICT(requirement_id,occurrence) DO NOTHING")
            .param("id",order).update();
    }
    @Transactional
    public Detail assign(UUID id,Assignment p,UUID actor) {
        guard(); var t=task(id); editable(t,p.version()); rule(!t.status().equals("INSTALLED") && t.pieceId()==null,"TASK_RETURN_BEFORE_ASSIGN");
        rule(db.sql("SELECT count(*) FROM stock_pieces WHERE requirement_id=:req AND status='ISSUED' AND task_id IS NULL").param("req",t.requirementId()).query(Long.class).single()==0,"TASK_RETURN_BEFORE_ASSIGN");
        rule(p.workerId()==null || eligible(p.workerId()),"TASK_WORKER_INVALID");
        rule(t.workerId()==null || Objects.equals(t.workerId(),p.workerId()) || !p.notes().isBlank(),"TASK_ASSIGN_REASON_REQUIRED");
        if(Objects.equals(t.workerId(),p.workerId())) return detail(id,actor,true);
        work.close(id,"ASSIGNMENT_CHANGED");
        db.sql("UPDATE assembly_tasks SET worker_id=:worker,status='PENDING',started_at=NULL,version=version+1,assignment_epoch=assignment_epoch+1 WHERE id=:id")
            .param("id",id).param("worker",p.workerId(),Types.OTHER).update();
        if(p.workerId()!=null) notifications.emit(NotificationWriter.Kind.TASK_ASSIGNED,t.orderId(),id,Long.toString(t.version()+1),actor);
        event(t,p.workerId()==null?"UNASSIGNED":"ASSIGNED",actor,p.workerId(),null,p.notes()); syncOrder(t.orderId(),actor); return detail(id,actor,true);
    }
    @Transactional
    public UUID issued(UUID requirement,UUID recipient,UUID piece,UUID actor) {
        guard();
        var ids=db.sql("""
            SELECT t.id FROM assembly_tasks t WHERE t.requirement_id=:req AND t.worker_id=:worker AND t.status<>'INSTALLED'
              AND NOT EXISTS(SELECT 1 FROM stock_pieces s WHERE s.task_id=t.id) ORDER BY t.occurrence LIMIT 1
            """).param("req",requirement).param("worker",recipient).query(UUID.class).list();
        if(ids.isEmpty()) {
            rule(db.sql("SELECT count(*) FROM assembly_tasks WHERE requirement_id=:req AND worker_id IS NOT NULL").param("req",requirement).query(Long.class).single()==0,"TASK_RECIPIENT_MISMATCH");
            return null;
        }
        rule(eligible(recipient),"TASK_WORKER_INVALID"); UUID id=ids.getFirst(); var t=task(id);
        db.sql("UPDATE assembly_tasks SET version=version+1 WHERE id=:id").param("id",id).update(); event(t,"PIECE_ISSUED",actor,recipient,piece,""); return id;
    }
    @Transactional
    public void returned(UUID taskId,UUID piece,UUID actor,String notes) {
        if(taskId==null) return; guard(); var t=task(taskId);
        work.close(taskId,"PIECE_RETURNED");
        db.sql("UPDATE assembly_tasks SET status='PENDING',started_at=NULL,version=version+1 WHERE id=:id").param("id",taskId).update();
        event(t,"PIECE_RETURNED",actor,t.workerId(),piece,notes); syncOrder(t.orderId(),actor);
    }
    private void physicalReferences(Task t) {
        rule(db.sql("SELECT active FROM machines WHERE id=:id").param("id",t.machineId()).query(Boolean.class).single(),"MACHINE_INACTIVE");
        rule(db.sql("SELECT p.active FROM parts p JOIN order_requirements r ON r.part_id=p.id WHERE r.id=:id").param("id",t.requirementId()).query(Boolean.class).single(),"PART_INACTIVE");
    }
    @Transactional
    public Detail reject(UUID id,Removal p,UUID actor,boolean all) {
        guard(); var t=scoped(id,actor,all); editable(t,p.version()); rule(actor.equals(t.workerId()),"TASK_NOT_YOURS");
        rule("ISSUED".equals(t.pieceStatus()) && !t.status().equals("INSTALLED"),"DEFECT_ISSUED_REQUIRED");
        work.close(id,"PIECE_REJECTED");
        db.sql("UPDATE stock_pieces SET status='REJECTED',version=version+1 WHERE id=:id").param("id",t.pieceId()).update();
        db.sql("UPDATE assembly_tasks SET status='PENDING',started_at=NULL,version=version+1 WHERE id=:id").param("id",id).update();
        stockMovement(t,"REJECTED",actor,p.reason()); event(t,"REJECTED",actor,actor,t.pieceId(),p.reason()); syncOrder(t.orderId(),actor);
        return detail(id,actor,true);
    }
    @Transactional
    public Detail start(UUID id,Action p,UUID actor,boolean all) {
        guard(); var t=scoped(id,actor,all); editable(t,p.version()); rule(actor.equals(t.workerId()),"TASK_NOT_YOURS");
        rule(t.status().equals("PENDING") && "ISSUED".equals(t.pieceStatus()),"TASK_PIECE_REQUIRED"); physicalReferences(t);
        work.start(id,actor,false);
        db.sql("UPDATE assembly_tasks SET status='IN_PROGRESS',started_at=CURRENT_TIMESTAMP,version=version+1 WHERE id=:id").param("id",id).update();
        event(t,"STARTED",actor,t.workerId(),t.pieceId(),p.notes()); syncOrder(t.orderId(),actor); return detail(id,actor,true);
    }
    private void stockMovement(Task t,String action,UUID actor,String notes) {
        db.sql("INSERT INTO stock_movements(id,piece_id,action,requirement_id,recipient_id,actor_id,reference,notes) VALUES (:id,:piece,:action,:req,:worker,:actor,'',:notes)")
            .param("id",UUID.randomUUID()).param("piece",t.pieceId()).param("action",action).param("req",t.requirementId()).param("worker",t.workerId()).param("actor",actor).param("notes",notes.trim()).update();
    }
    @Transactional
    public Detail timer(UUID id,Action p,UUID actor,boolean all,boolean resume,boolean force) {
        guard(); var t=scoped(id,actor,all); editable(t,p.version());
        rule(force || actor.equals(t.workerId()),"TASK_NOT_YOURS");
        rule(t.status().equals("IN_PROGRESS") && "ISSUED".equals(t.pieceStatus()),"TASK_START_REQUIRED");
        rule(!p.notes().isBlank(),"TIME_REASON_REQUIRED");
        if(resume) work.start(id,t.workerId(),true);
        else { rule(t.workRunning(),"TIME_NOT_RUNNING"); work.close(id,p.notes()); }
        db.sql("UPDATE assembly_tasks SET version=version+1 WHERE id=:id").param("id",id).update();
        event(t,force?"TIME_STOPPED":resume?"RESUMED":"PAUSED",actor,t.workerId(),t.pieceId(),p.notes()); return detail(id,actor,true);
    }
    @Transactional
    public Detail install(UUID id,Action p,UUID actor,boolean all) {
        guard(); var t=scoped(id,actor,all); editable(t,p.version()); rule(actor.equals(t.workerId()),"TASK_NOT_YOURS");
        rule(t.status().equals("IN_PROGRESS") && "ISSUED".equals(t.pieceStatus()),"TASK_START_REQUIRED"); physicalReferences(t);
        rule(db.sql("SELECT count(*) FROM stock_pieces WHERE id=:piece AND recipient_id=:worker AND requirement_id=:req").param("piece",t.pieceId()).param("worker",actor).param("req",t.requirementId()).query(Long.class).single()==1,"TASK_RECIPIENT_MISMATCH");
        rule(db.sql("SELECT count(*) FROM installations WHERE machine_id=:machine AND position_code=:position AND occurrence=:occurrence AND removed_at IS NULL")
            .param("machine",t.machineId()).param("position",t.positionCode()).param("occurrence",t.occurrence()).query(Long.class).single()==0,"TASK_SLOT_OCCUPIED");
        db.sql("""
            INSERT INTO installations(id,task_id,piece_id,machine_id,position_code,occurrence,worker_id,started_at,installed_by,install_notes)
            VALUES (:id,:task,:piece,:machine,:position,:occurrence,:worker,:started,:actor,:notes)
            """).param("id",UUID.randomUUID()).param("task",id).param("piece",t.pieceId()).param("machine",t.machineId()).param("position",t.positionCode()).param("occurrence",t.occurrence())
            .param("worker",actor).param("started",Timestamp.from(t.startedAt())).param("actor",actor).param("notes",p.notes().trim()).update();
        db.sql("UPDATE assembly_tasks SET status='INSTALLED',version=version+1 WHERE id=:id").param("id",id).update();
        db.sql("UPDATE stock_pieces SET status='INSTALLED',version=version+1 WHERE id=:id").param("id",t.pieceId()).update();
        db.sql("UPDATE machines SET configuration_version=configuration_version+1,version=version+1 WHERE id=:id").param("id",t.machineId()).update();
        operations.configurationChanged(t.machineId(),actor);
        work.close(id,"INSTALLED");
        stockMovement(t,"INSTALLED",actor,p.notes()); event(t,"INSTALLED",actor,actor,t.pieceId(),p.notes()); syncOrder(t.orderId(),actor); return detail(id,actor,true);
    }
    @Transactional
    public Detail uninstall(UUID id,Removal p,UUID actor,boolean all) {
        guard(); var t=scoped(id,actor,all); editable(t,p.version()); rule(t.status().equals("INSTALLED") && t.installationId()!=null,"TASK_NOT_INSTALLED");
        work.rememberIncompleteHistory(id);
        db.sql("UPDATE installations SET removed_at=CURRENT_TIMESTAMP,removed_by=:actor,removal_reason=:reason WHERE id=:id")
            .param("id",t.installationId()).param("actor",actor).param("reason",p.reason().trim()).update();
        db.sql("UPDATE assembly_tasks SET status='IN_PROGRESS',started_at=CURRENT_TIMESTAMP,version=version+1 WHERE id=:id").param("id",id).update();
        db.sql("UPDATE stock_pieces SET status='ISSUED',version=version+1 WHERE id=:id").param("id",t.pieceId()).update();
        db.sql("UPDATE machines SET configuration_version=configuration_version+1,version=version+1 WHERE id=:id").param("id",t.machineId()).update();
        operations.configurationChanged(t.machineId(),actor);
        stockMovement(t,"UNINSTALLED",actor,p.reason()); event(t,"UNINSTALLED",actor,t.workerId(),t.pieceId(),p.reason()); syncOrder(t.orderId(),actor); return detail(id,actor,true);
    }
    public MachineAssembly machine(UUID id) {
        var history=db.sql("""
            SELECT i.*,r.order_id,r.part_code,r.part_name,o.code order_code,s.serial_number,u.display_name worker_name,a.display_name removed_name
            FROM installations i JOIN assembly_tasks t ON t.id=i.task_id JOIN order_requirements r ON r.id=t.requirement_id JOIN manufacturing_orders o ON o.id=r.order_id
            JOIN stock_pieces s ON s.id=i.piece_id JOIN users u ON u.id=i.worker_id LEFT JOIN users a ON a.id=i.removed_by WHERE i.machine_id=:id ORDER BY i.installed_at DESC,i.id
            """).param("id",id).query((r,n)->new Installation(r.getObject("id",UUID.class),r.getObject("task_id",UUID.class),r.getObject("order_id",UUID.class),r.getString("order_code"),r.getString("position_code"),r.getInt("occurrence"),
                r.getString("serial_number"),r.getString("part_code"),r.getString("part_name"),r.getString("worker_name"),time(r,"started_at"),time(r,"installed_at"),r.getString("install_notes"),r.getString("removed_name"),time(r,"removed_at"),r.getString("removal_reason"))).list();
        return db.sql("SELECT serial_number,name,configuration_version FROM machines WHERE id=:id").param("id",id)
            .query((r,n)->new MachineAssembly(id,r.getString("serial_number"),r.getString("name"),r.getLong("configuration_version"),history)).optional().orElseThrow(AssemblyService::missing);
    }
}

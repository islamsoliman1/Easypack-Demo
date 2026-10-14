package com.easyback.operations;

import static com.easyback.operations.OperationsData.*;
import com.easyback.identity.ApiException;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class OperationsService {
    private final JdbcClient db;
    public OperationsService(JdbcClient db) { this.db=db; }
    private static void rule(boolean valid,String code) { if(!valid) throw new ApiException(409,code,"Operations action is not allowed."); }
    private static ApiException missing() { return new ApiException(404,"NOT_FOUND","Operations record not found."); }
    private void guard() {
        db.sql("SELECT id FROM security_guard WHERE id=1 FOR UPDATE").query(Integer.class).single();
        db.sql("SELECT id FROM catalog_guard WHERE id=1 FOR UPDATE").query(Integer.class).single();
    }
    private static Instant time(ResultSet r,String field) throws SQLException { var t=r.getTimestamp(field); return t==null?null:t.toInstant(); }
    private static final String ORDERS="""
        SELECT o.*,m.configuration_version,
          (SELECT coalesce(sum(r.quantity),0) FROM order_requirements r WHERE r.order_id=o.id) required_count,
          (SELECT count(*) FROM assembly_tasks t JOIN order_requirements r ON r.id=t.requirement_id
            JOIN stock_pieces s ON s.task_id=t.id AND s.status='INSTALLED'
            JOIN installations i ON i.task_id=t.id AND i.piece_id=s.id AND i.removed_at IS NULL
            WHERE r.order_id=o.id AND t.status='INSTALLED' AND i.machine_id=o.machine_id) installed_count,
          (SELECT count(*) FROM defect_cases d JOIN assembly_tasks t ON t.id=d.task_id JOIN order_requirements r ON r.id=t.requirement_id
            WHERE r.order_id=o.id AND d.status NOT IN ('RETURNED','SCRAPPED')) blocking_defects,
          (SELECT x.id FROM operations_trials x WHERE x.order_id=o.id ORDER BY x.started_at DESC,x.id DESC LIMIT 1) latest_trial_id
        FROM manufacturing_orders o JOIN machines m ON m.id=o.machine_id
        """;
    private static final RowMapper<Summary> ORDER=(r,n)->new Summary(r.getObject("id",UUID.class),r.getString("code"),r.getString("title"),
        r.getObject("machine_id",UUID.class),r.getString("machine_serial"),r.getString("machine_name"),r.getString("status"),r.getLong("version"),
        r.getLong("configuration_version"),r.getLong("required_count"),r.getLong("installed_count"),r.getLong("blocking_defects"),r.getBoolean("rework_required"),
        time(r,"delivery_at"),time(r,"assembly_completed_at"),time(r,"tested_at"),time(r,"completed_at"),time(r,"delivered_at"),r.getString("delivery_notes"),r.getObject("latest_trial_id",UUID.class));
    private Summary order(UUID id) { return db.sql(ORDERS+" WHERE o.id=:id AND o.status<>'DRAFT'").param("id",id).query(ORDER).optional().orElseThrow(OperationsService::missing); }
    public List<Summary> orders() { return db.sql(ORDERS+" WHERE o.status<>'DRAFT' ORDER BY o.delivery_at,o.code").query(ORDER).list(); }
    private Trial trial(UUID id) {
        var checks=db.sql("SELECT * FROM operations_checks WHERE trial_id=:id ORDER BY ordinal").param("id",id)
            .query((r,n)->new Check(r.getObject("id",UUID.class),r.getString("label"),r.getString("criterion"),r.getString("result"),r.getString("notes"))).list();
        var configuration=db.sql("SELECT * FROM operations_configuration WHERE trial_id=:id ORDER BY position_code,occurrence").param("id",id)
            .query((r,n)->new Configuration(r.getObject("installation_id",UUID.class),r.getString("position_code"),r.getInt("occurrence"),r.getString("serial_number"),r.getString("part_code"),r.getString("part_name"))).list();
        return db.sql("""
            SELECT t.*,a.display_name started_name,b.display_name finished_name,c.display_name resolved_name
            FROM operations_trials t JOIN users a ON a.id=t.started_by LEFT JOIN users b ON b.id=t.finished_by
            LEFT JOIN users c ON c.id=t.resolved_by WHERE t.id=:id
            """).param("id",id).query((r,n)->new Trial(id,r.getObject("order_id",UUID.class),r.getLong("configuration_version"),r.getString("status"),r.getLong("version"),
                r.getString("started_name"),time(r,"started_at"),r.getString("start_notes"),r.getString("finished_name"),time(r,"finished_at"),r.getString("result_notes"),
                r.getString("resolved_name"),time(r,"resolved_at"),r.getString("resolution_notes"),checks,configuration)).optional().orElseThrow(OperationsService::missing);
    }
    public Detail detail(UUID id) {
        var o=order(id); var trials=db.sql("SELECT id FROM operations_trials WHERE order_id=:id ORDER BY started_at DESC,id DESC").param("id",id).query(UUID.class).list().stream().map(this::trial).toList();
        var events=db.sql("SELECT e.*,u.display_name FROM operations_events e JOIN users u ON u.id=e.actor_id WHERE e.order_id=:id ORDER BY e.created_at,e.id")
            .param("id",id).query((r,n)->new Event(r.getObject("id",UUID.class),r.getObject("trial_id",UUID.class),r.getString("action"),r.getString("display_name"),r.getString("notes"),time(r,"created_at"))).list();
        return new Detail(o,trials,events);
    }
    private void event(UUID order,UUID trial,String action,UUID actor,String notes) {
        db.sql("INSERT INTO operations_events(id,order_id,trial_id,action,actor_id,notes) VALUES (:id,:order,:trial,:action,:actor,:notes)")
            .param("id",UUID.randomUUID()).param("order",order).param("trial",trial,Types.OTHER).param("action",action).param("actor",actor).param("notes",notes.trim()).update();
        db.sql("INSERT INTO audit_logs(id,actor_id,action,target_id) VALUES (:id,:actor,:action,:target)")
            .param("id",UUID.randomUUID()).param("actor",actor).param("action","OPERATIONS_"+action).param("target",order).update();
    }
    private void ready(Summary o) {
        rule(o.requiredCount()>0 && o.requiredCount()==o.installedCount(),"OPERATIONS_ASSEMBLY_INCOMPLETE");
        rule(o.blockingDefects()==0,"OPERATIONS_OPEN_DEFECTS");
        rule(db.sql("SELECT count(*) FROM stock_pieces s JOIN order_requirements r ON r.id=s.requirement_id WHERE r.order_id=:id AND s.status IN ('ISSUED','REJECTED')")
            .param("id",o.id()).query(Long.class).single()==0,"OPERATIONS_OPEN_CUSTODY");
        rule(db.sql("SELECT count(*) FROM raw_units u JOIN preparation_requests p ON p.id=u.preparation_id JOIN order_requirements r ON r.id=p.requirement_id WHERE r.order_id=:id AND u.status='ISSUED'")
            .param("id",o.id()).query(Long.class).single()==0,"OPERATIONS_OPEN_CUSTODY");
        rule(db.sql("SELECT active FROM machines WHERE id=:id").param("id",o.machineId()).query(Boolean.class).single(),"MACHINE_INACTIVE");
    }
    @Transactional
    public Detail start(UUID id,Start p,UUID actor) {
        guard(); var o=order(id); rule(o.version()==p.orderVersion(),"STALE_VERSION"); rule(o.configurationVersion()==p.configurationVersion(),"OPERATIONS_CONFIGURATION_CHANGED");
        rule(o.status().equals("AWAITING_TESTING") && !o.reworkRequired(),"OPERATIONS_ORDER_STATE"); ready(o);
        rule(db.sql("SELECT count(*) FROM operations_trials WHERE machine_id=:id AND status='RUNNING'").param("id",o.machineId()).query(Long.class).single()==0,"OPERATIONS_ALREADY_RUNNING");
        UUID trial=UUID.randomUUID();
        db.sql("INSERT INTO operations_trials(id,order_id,machine_id,configuration_version,started_by,start_notes) VALUES (:id,:order,:machine,:configuration,:actor,:notes)")
            .param("id",trial).param("order",id).param("machine",o.machineId()).param("configuration",o.configurationVersion()).param("actor",actor).param("notes",p.notes().trim()).update();
        int ordinal=0; for(var check:p.checks()) db.sql("INSERT INTO operations_checks(id,trial_id,ordinal,label,criterion) VALUES (:id,:trial,:ordinal,:label,:criterion)")
            .param("id",UUID.randomUUID()).param("trial",trial).param("ordinal",++ordinal).param("label",check.label().trim()).param("criterion",check.criterion().trim()).update();
        db.sql("""
            INSERT INTO operations_configuration(trial_id,installation_id,position_code,occurrence,serial_number,part_code,part_name)
            SELECT :trial,i.id,i.position_code,i.occurrence,s.serial_number,r.part_code,r.part_name
            FROM installations i JOIN stock_pieces s ON s.id=i.piece_id JOIN assembly_tasks t ON t.id=i.task_id JOIN order_requirements r ON r.id=t.requirement_id
            WHERE i.machine_id=:machine AND i.removed_at IS NULL
            """).param("trial",trial).param("machine",o.machineId()).update();
        db.sql("UPDATE manufacturing_orders SET status='TESTING',version=version+1 WHERE id=:id").param("id",id).update();
        event(id,trial,"STARTED",actor,p.notes()); return detail(id);
    }
    private Trial running(UUID id,long version) {
        var t=trial(id); rule(t.version()==version,"STALE_VERSION"); rule(t.status().equals("RUNNING"),"OPERATIONS_TRIAL_CLOSED");
        rule(order(t.orderId()).status().equals("TESTING"),"OPERATIONS_ORDER_STATE"); return t;
    }
    private void close(Trial t,String status,UUID actor,String notes) {
        db.sql("UPDATE operations_trials SET status=:status,finished_by=:actor,finished_at=CURRENT_TIMESTAMP,result_notes=:notes,version=version+1 WHERE id=:id")
            .param("id",t.id()).param("status",status).param("actor",actor).param("notes",notes.trim()).update();
        event(t.orderId(),t.id(),status,actor,notes);
    }
    @Transactional
    public Detail finish(UUID id,Finish p,UUID actor) {
        guard(); var t=running(id,p.version()); var o=order(t.orderId());
        rule(o.configurationVersion()==t.configurationVersion(),"OPERATIONS_CONFIGURATION_CHANGED"); ready(o);
        var expected=new HashSet<>(t.checks().stream().map(Check::id).toList()); var actual=new HashSet<>(p.checks().stream().map(Result::id).toList());
        rule(expected.equals(actual) && p.checks().size()==actual.size(),"OPERATIONS_CHECKS_INVALID");
        boolean failed=p.checks().stream().anyMatch(c->c.result()==CheckResult.FAIL);
        rule((p.outcome()==Outcome.FAILED)==failed,"OPERATIONS_RESULT_INVALID");
        rule(p.checks().stream().allMatch(c->c.result()!=CheckResult.FAIL || !c.notes().isBlank()),"OPERATIONS_FAILURE_REASON");
        for(var c:p.checks()) db.sql("UPDATE operations_checks SET result=:result,notes=:notes WHERE id=:id AND trial_id=:trial")
            .param("id",c.id()).param("trial",id).param("result",c.result().name()).param("notes",c.notes().trim()).update();
        close(t,p.outcome().name(),actor,p.notes());
        if(p.outcome()==Outcome.PASSED) db.sql("UPDATE manufacturing_orders SET status='COMPLETED',tested_at=CURRENT_TIMESTAMP,completed_at=CURRENT_TIMESTAMP,passed_trial_id=:trial,rework_required=false,version=version+1 WHERE id=:id")
            .param("id",o.id()).param("trial",id).update();
        else db.sql("UPDATE manufacturing_orders SET status='REWORK',tested_at=CURRENT_TIMESTAMP,rework_required=true,version=version+1 WHERE id=:id").param("id",o.id()).update();
        return detail(o.id());
    }
    @Transactional
    public Detail abort(UUID id,Action p,UUID actor) {
        guard(); var t=running(id,p.version()); close(t,"ABORTED",actor,p.notes());
        db.sql("UPDATE manufacturing_orders SET status='REWORK',rework_required=true,version=version+1 WHERE id=:id").param("id",t.orderId()).update(); return detail(t.orderId());
    }
    @Transactional
    public Detail resolve(UUID id,Resolution p,UUID actor) {
        guard(); var t=trial(id); var o=order(t.orderId()); rule(t.version()==p.version() && o.version()==p.orderVersion(),"STALE_VERSION");
        rule(Set.of("FAILED","INVALIDATED","ABORTED").contains(t.status()) && t.resolvedAt()==null && id.equals(o.latestTrialId())
            && o.reworkRequired() && o.status().equals("REWORK"),"OPERATIONS_ORDER_STATE"); ready(o);
        db.sql("UPDATE operations_trials SET resolved_by=:actor,resolved_at=CURRENT_TIMESTAMP,resolution_notes=:notes,version=version+1 WHERE id=:id")
            .param("id",id).param("actor",actor).param("notes",p.notes().trim()).update();
        db.sql("UPDATE manufacturing_orders SET status='AWAITING_TESTING',rework_required=false,assembly_completed_at=coalesce(assembly_completed_at,CURRENT_TIMESTAMP),version=version+1 WHERE id=:id").param("id",o.id()).update();
        event(o.id(),id,"RESOLVED",actor,p.notes()); return detail(o.id());
    }
    @Transactional
    public Detail deliver(UUID id,Action p,UUID actor) {
        guard(); var o=order(id); rule(o.version()==p.version(),"STALE_VERSION"); rule(o.status().equals("COMPLETED") && o.deliveredAt()==null,"OPERATIONS_ORDER_STATE"); ready(o);
        var passed=db.sql("SELECT configuration_version FROM operations_trials WHERE id=(SELECT passed_trial_id FROM manufacturing_orders WHERE id=:id) AND status='PASSED'")
            .param("id",id).query(Long.class).optional();
        rule(passed.isPresent() && passed.get()==o.configurationVersion(),"OPERATIONS_CONFIGURATION_CHANGED");
        db.sql("UPDATE manufacturing_orders SET delivered_at=CURRENT_TIMESTAMP,delivered_by=:actor,delivery_notes=:notes,version=version+1 WHERE id=:id")
            .param("id",id).param("actor",actor).param("notes",p.notes().trim()).update();
        event(id,null,"DELIVERED",actor,p.notes()); return detail(id);
    }
    @Transactional
    public void configurationChanged(UUID machine,UUID actor) {
        guard();
        rule(db.sql("SELECT count(*) FROM manufacturing_orders WHERE machine_id=:id AND status='COMPLETED' AND delivered_at IS NULL")
            .param("id",machine).query(Long.class).single()==0,"OPERATIONS_COMPLETED_CONFIGURATION_LOCKED");
        var ids=db.sql("SELECT id FROM operations_trials WHERE machine_id=:id AND status='RUNNING'").param("id",machine).query(UUID.class).list();
        for(UUID id:ids) {
            var t=trial(id); close(t,"INVALIDATED",actor,"Machine configuration changed during the trial");
            db.sql("UPDATE manufacturing_orders SET status='REWORK',rework_required=true,version=version+1 WHERE id=:id").param("id",t.orderId()).update();
        }
    }
}

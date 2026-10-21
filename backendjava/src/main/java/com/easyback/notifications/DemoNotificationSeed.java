package com.easyback.notifications;

import com.easyback.identity.DemoBootstrap;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.easyback.notifications.NotificationWriter.Kind.*;

/** One-time snapshots of existing synthetic resources, never a reset or invented transition. */
@Service
@Profile("demo")
@Transactional(propagation=Propagation.MANDATORY)
public class DemoNotificationSeed {
    private final JdbcClient db;
    private final NotificationWriter writer;
    public DemoNotificationSeed(JdbcClient db,NotificationWriter writer) { this.db=db; this.writer=writer; }
    public void seed() {
        if(db.sql("SELECT completed FROM notification_demo_seed WHERE id=1 FOR UPDATE").query(Boolean.class).single()) return;
        db.sql("SELECT id FROM catalog_guard WHERE id=1 FOR UPDATE").query(Integer.class).single();
        snapshot(DEMO_TASK_SNAPSHOT,"SELECT o.id order_id,t.id context FROM assembly_tasks t JOIN order_requirements r ON r.id=t.requirement_id JOIN manufacturing_orders o ON o.id=r.order_id WHERE o.code LIKE 'DEMO-ORDER-%' AND t.worker_id='"+DemoBootstrap.VISITOR+"' ORDER BY (t.status='PENDING') DESC,o.code,t.id LIMIT 1");
        snapshot(DEMO_PREPARATION_SNAPSHOT,"SELECT o.id order_id,p.id context FROM preparation_requests p JOIN order_requirements r ON r.id=p.requirement_id JOIN manufacturing_orders o ON o.id=r.order_id WHERE o.code LIKE 'DEMO-ORDER-%' ORDER BY o.code,p.id LIMIT 1");
        snapshot(DEMO_DEFECT_SNAPSHOT,"SELECT o.id order_id,d.id context FROM defect_cases d JOIN assembly_tasks t ON t.id=d.task_id JOIN order_requirements r ON r.id=t.requirement_id JOIN manufacturing_orders o ON o.id=r.order_id WHERE o.code LIKE 'DEMO-ORDER-%' ORDER BY o.code,d.id LIMIT 1");
        snapshot(DEMO_OPERATIONS_SNAPSHOT,"SELECT id order_id,NULL::uuid context FROM manufacturing_orders WHERE code LIKE 'DEMO-ORDER-%' AND status<>'DRAFT' ORDER BY (status='AWAITING_TESTING') DESC,code LIMIT 1");
        snapshot(DEMO_ORDER_SNAPSHOT,"SELECT id order_id,NULL::uuid context FROM manufacturing_orders WHERE code LIKE 'DEMO-ORDER-%' ORDER BY code LIMIT 1");
        db.sql("UPDATE notification_demo_seed SET completed=true WHERE id=1").update();
    }
    private void snapshot(NotificationWriter.Kind kind,String query) {
        db.sql(query).query((r,n)->new UUID[]{r.getObject("order_id",UUID.class),r.getObject("context",UUID.class)}).optional()
            .ifPresent(row->writer.emit(kind,row[0],row[1],"demo-snapshot-v1",null));
    }
}

package com.easyback.notifications;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.easyback.notifications.NotificationWriter.Kind.*;

@Service
public class DeliveryReminderEvaluator {
    private final JdbcClient db;
    private final Clock clock;
    private final NotificationWriter writer;
    public DeliveryReminderEvaluator(JdbcClient db,Clock clock,NotificationWriter writer) { this.db=db; this.clock=clock; this.writer=writer; }
    public List<UUID> candidates() {
        return db.sql("SELECT id FROM manufacturing_orders WHERE opened_at IS NOT NULL AND status<>'CANCELLED' AND delivered_at IS NULL AND delivery_at<=:until")
            .param("until",Timestamp.from(clock.instant().plus(Duration.ofHours(24)))).query(UUID.class).list();
    }
    @Transactional
    public void evaluate(UUID order) {
        db.sql("SELECT id FROM security_guard WHERE id=1 FOR UPDATE").query(Integer.class).single();
        db.sql("SELECT id FROM catalog_guard WHERE id=1 FOR UPDATE").query(Integer.class).single();
        var due=db.sql("SELECT delivery_at FROM manufacturing_orders WHERE id=:id AND opened_at IS NOT NULL AND status<>'CANCELLED' AND delivered_at IS NULL")
            .param("id",order).query((r,n)->r.getTimestamp(1).toInstant()).optional();
        if(due.isEmpty()) return;
        var now=clock.instant(); var deadline=due.get();
        if(!now.isBefore(deadline)) writer.emit(DELIVERY_OVERDUE,order,null,deadline.toString(),null);
        else if(!deadline.isAfter(now.plus(Duration.ofHours(24)))) {
            // Clock rollback must never create an upcoming alert after an overdue occurrence.
            if(db.sql("SELECT count(*) FROM notifications WHERE dedupe_key=:key").param("key",DELIVERY_OVERDUE+":"+order+":"+deadline).query(Long.class).single()==0)
                writer.emit(DELIVERY_DUE_SOON,order,null,deadline.toString(),null);
        }
    }
}

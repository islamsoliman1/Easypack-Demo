package com.easyback.notifications;

import java.sql.Types;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import com.easyback.identity.DemoBootstrap;

/** Persists safe, frozen event summaries inside the originating business transaction. */
@Service
@Transactional(propagation=Propagation.MANDATORY)
public class NotificationWriter {
    public enum Kind {
        TASK_ASSIGNED("تم إسناد مهمة إليك","INFO"), ORDER_RELEASED("تم فتح طلب التصنيع","INFO"),
        ORDER_SHORTAGE("نواقص في طلب التصنيع","WARNING"), PREPARATION_REQUESTED("طلب تجهيز قطع","INFO"),
        PREPARATION_FULFILLED("اكتمل تجهيز القطع","INFO"), DEFECT_REPORTED("تم الإبلاغ عن قطعة معيبة","WARNING"),
        DEFECT_RECEIVED("قطعة معيبة جاهزة للإصلاح","WARNING"), REPAIR_COMPLETED("قطعة مصلحة جاهزة للاستلام","INFO"),
        ORDER_READY_FOR_TEST("الطلب جاهز لتجربة التشغيل","INFO"), TRIAL_FAILED("فشلت تجربة التشغيل","URGENT"),
        TRIAL_ABORTED("تم إيقاف تجربة التشغيل","WARNING"), TRIAL_INVALIDATED("تغير تركيب الماكينة أثناء التجربة","URGENT"),
        ORDER_COMPLETED("اكتمل طلب التصنيع","INFO"), DELIVERY_DUE_SOON("اقترب موعد التسليم","WARNING"),
        DELIVERY_OVERDUE("تأخر موعد التسليم","URGENT"),
        DEMO_TASK_SNAPSHOT("مثال: مهمة تجميع","INFO"), DEMO_PREPARATION_SNAPSHOT("مثال: طلب تجهيز","INFO"),
        DEMO_DEFECT_SNAPSHOT("مثال: بلاغ عيب","WARNING"), DEMO_OPERATIONS_SNAPSHOT("مثال: تجربة تشغيل","INFO"),
        DEMO_ORDER_SNAPSHOT("مثال: طلب تصنيع","INFO");
        final String title,severity;
        Kind(String title,String severity) { this.title=title; this.severity=severity; }
    }
    private static final Logger log=LoggerFactory.getLogger(NotificationWriter.class);
    private final JdbcClient db;
    private final Clock clock;
    private final boolean demo;
    private final boolean includeActor;
    public NotificationWriter(JdbcClient db,Clock clock,Environment environment,@Value("${easyback.notifications.include-actor:false}") boolean includeActor) {
        this.db=db; this.clock=clock; this.demo=environment.acceptsProfiles(Profiles.of("demo")); this.includeActor=demo && includeActor;
    }
    private record Person(UUID id,Set<String> permissions) { boolean has(String... p) { return permissions.containsAll(List.of(p)); } }
    private record Destination(String scope,UUID id,String[] required) {}
    private Destination dest(String scope,UUID id,String... required) { return new Destination(scope,id,required); }

    public void emit(Kind kind,UUID order,UUID context,String occurrence,UUID actor) {
        // Initial synthetic workflow construction is represented by five explicit snapshot examples.
        if(demo && DemoBootstrap.VISITOR.equals(actor) && !db.sql("SELECT seeded FROM demo_installation WHERE id=1").query(Boolean.class).single()) return;
        var head=db.sql("SELECT responsible_id,code FROM manufacturing_orders WHERE id=:id").param("id",order)
            .query((r,n)->Map.entry(r.getObject(1,UUID.class),r.getString(2))).single();
        UUID task=Set.of(Kind.TASK_ASSIGNED,Kind.DEMO_TASK_SNAPSHOT).contains(kind)?context:null;
        UUID prep=Set.of(Kind.PREPARATION_REQUESTED,Kind.PREPARATION_FULFILLED,Kind.DEMO_PREPARATION_SNAPSHOT).contains(kind)?context:null;
        UUID defect=Set.of(Kind.DEFECT_REPORTED,Kind.DEFECT_RECEIVED,Kind.REPAIR_COMPLETED,Kind.DEMO_DEFECT_SNAPSHOT).contains(kind)?context:null;
        UUID trial=Set.of(Kind.TRIAL_FAILED,Kind.TRIAL_ABORTED,Kind.TRIAL_INVALIDATED,Kind.ORDER_COMPLETED).contains(kind)?context:null;
        UUID worker=null; Long epoch=null;
        if(task!=null) {
            var assignment=db.sql("SELECT t.worker_id,t.assignment_epoch FROM assembly_tasks t JOIN order_requirements r ON r.id=t.requirement_id WHERE t.id=:task AND r.order_id=:order")
                .param("task",task).param("order",order).query((r,n)->new Object[]{r.getObject(1,UUID.class),r.getLong(2)}).single();
            worker=(UUID)assignment[0]; epoch=(Long)assignment[1];
        }
        if(prep!=null) requireContext("SELECT count(*) FROM preparation_requests p JOIN order_requirements r ON r.id=p.requirement_id WHERE p.id=:id AND r.order_id=:order",prep,order);
        if(defect!=null) requireContext("SELECT count(*) FROM defect_cases d JOIN assembly_tasks t ON t.id=d.task_id JOIN order_requirements r ON r.id=t.requirement_id WHERE d.id=:id AND r.order_id=:order",defect,order);
        if(trial!=null) requireContext("SELECT count(*) FROM operations_trials WHERE id=:id AND order_id=:order",trial,order);
        var now=Timestamp.from(clock.instant()); UUID id=UUID.randomUUID();
        String key=kind+":"+(context==null?order:context)+":"+occurrence;
        int inserted=db.sql("""
            INSERT INTO notifications(id,type,severity,actor_id,order_id,order_code,sample,task_id,preparation_id,defect_id,trial_id,assignment_epoch,dedupe_key,created_at)
            VALUES (:id,:type,:severity,:actor,:order,:code,:sample,:task,:prep,:defect,:trial,:epoch,:key,:now) ON CONFLICT(dedupe_key) DO NOTHING
            """).param("id",id).param("type",kind.name()).param("severity",kind.severity).param("actor",actor,Types.OTHER)
            .param("order",order).param("code",head.getValue()).param("sample",kind.name().startsWith("DEMO_")).param("task",task,Types.OTHER).param("prep",prep,Types.OTHER).param("defect",defect,Types.OTHER)
            .param("trial",trial,Types.OTHER).param("epoch",epoch,Types.BIGINT).param("key",key).param("now",now).update();
        if(inserted==0) return;
        var people=db.sql("SELECT id,permissions FROM notification_user_permissions").query((r,n)->new Person(r.getObject(1,UUID.class),Set.of((String[])r.getArray(2).getArray()))).list();
        int recipients=0;
        for(var p:people) {
            if(p.id.equals(actor) && !(includeActor && DemoBootstrap.VISITOR.equals(actor))) continue;
            Destination d=null;
            if(task==null && p.id.equals(head.getKey()) && (p.has("orders.view") || p.has("orders.view-assigned"))) d=dest("ORDER",order);
            Destination action=switch(kind) {
                case TASK_ASSIGNED,DEMO_TASK_SNAPSHOT -> p.id.equals(worker) && p.has("tasks.work") && (p.has("tasks.view-assigned") || p.has("tasks.view","orders.view")) ? dest("TASK",task,"tasks.work") : null;
                case DEMO_PREPARATION_SNAPSHOT -> p.has("orders.view","preparation.manage") ? dest("PREPARATION",prep,"orders.view","preparation.manage") : null;
                case DEMO_DEFECT_SNAPSHOT -> p.has("defects.view","orders.view") ? dest("DEFECT",defect,"defects.view","orders.view") : null;
                case DEMO_OPERATIONS_SNAPSHOT -> p.has("operations.view") ? dest("OPERATIONS",order,"operations.view") : null;
                case ORDER_SHORTAGE -> p.has("orders.view","stock.view","stock.reserve") ? dest("ORDER",order,"orders.view","stock.view","stock.reserve") : null;
                case PREPARATION_REQUESTED -> p.has("orders.view","preparation.manage") ? dest("PREPARATION",prep,"orders.view","preparation.manage") : null;
                case PREPARATION_FULFILLED -> p.has("orders.view","stock.view","stock.issue") ? dest("ORDER",order,"orders.view","stock.view","stock.issue") : null;
                case DEFECT_REPORTED -> p.has("defects.view","orders.view","stock.view","stock.return") ? dest("DEFECT",defect,"defects.view","orders.view","stock.view","stock.return") : p.has("defects.view","orders.view","defects.manage") ? dest("DEFECT",defect,"defects.view","orders.view","defects.manage") : null;
                case DEFECT_RECEIVED -> p.has("defects.view","orders.view","defects.manage") ? dest("DEFECT",defect,"defects.view","orders.view","defects.manage") : null;
                case REPAIR_COMPLETED -> p.has("defects.view","orders.view","stock.view","stock.receive") ? dest("DEFECT",defect,"defects.view","orders.view","stock.view","stock.receive") : null;
                case ORDER_READY_FOR_TEST -> p.has("operations.view","operations.start") ? dest("OPERATIONS",order,"operations.view","operations.start") : null;
                case TRIAL_FAILED,TRIAL_ABORTED,TRIAL_INVALIDATED -> p.has("operations.view","operations.resolve") ? dest("OPERATIONS",order,"operations.view","operations.resolve") : null;
                case ORDER_COMPLETED -> p.has("operations.view","operations.deliver") ? dest("OPERATIONS",order,"operations.view","operations.deliver") : null;
                case DELIVERY_DUE_SOON,DELIVERY_OVERDUE -> p.has("orders.view","orders.manage") ? dest("ORDER",order,"orders.view","orders.manage") : null;
                default -> null;
            };
            if(action!=null) d=action;
            if(d==null) continue;
            // Never include report reasons, trial notes or mutable resource titles in inbox content.
            db.sql("""
                INSERT INTO notification_recipients(notification_id,user_id,scope_type,scope_id,required_permissions,owner_fallback,title,body,created_at)
                VALUES (:id,:user,:scope,:target,:permissions,:fallback,:title,:body,:now)
                """).param("id",id).param("user",p.id).param("scope",d.scope).param("target",d.id)
                .param("permissions",d.required,Types.ARRAY).param("fallback",task==null && p.id.equals(head.getKey()) && (p.has("orders.view") || p.has("orders.view-assigned")))
                .param("title",kind.title).param("body","طلب التصنيع "+head.getValue()).param("now",now).update();
            recipients++;
        }
        if(recipients==0) log.warn("Notification has no eligible recipients: type={} event={} order={}",kind,id,order);
    }
    private void requireContext(String sql,UUID id,UUID order) {
        if(db.sql(sql).param("id",id).param("order",order).query(Long.class).single()!=1) throw new IllegalArgumentException("Notification context does not belong to order");
    }
    public void ready(UUID order,String before,UUID actor) {
        var state=db.sql("SELECT status,version FROM manufacturing_orders WHERE id=:id").param("id",order).query((r,n)->Map.entry(r.getString(1),r.getLong(2))).single();
        if(!"AWAITING_TESTING".equals(before) && "AWAITING_TESTING".equals(state.getKey())) emit(Kind.ORDER_READY_FOR_TEST,order,null,state.getValue().toString(),actor);
    }
    public void preparation(UUID order,UUID prep,String status,UUID actor) {
        long version=db.sql("SELECT version FROM preparation_requests WHERE id=:id").param("id",prep).query(Long.class).single();
        emit("FULFILLED".equals(status)?Kind.PREPARATION_FULFILLED:Kind.PREPARATION_REQUESTED,order,prep,Long.toString(version),actor);
    }
}

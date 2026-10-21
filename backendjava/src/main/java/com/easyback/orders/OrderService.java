package com.easyback.orders;

import static com.easyback.orders.OrderData.*;
import com.easyback.catalog.CatalogData.*;
import com.easyback.catalog.CatalogService;
import com.easyback.identity.ApiException;
import com.easyback.identity.IdentityStore;
import com.easyback.warehouse.StockService;
import com.easyback.assembly.AssemblyService;
import com.easyback.notifications.NotificationWriter;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
public class OrderService {
    private final JdbcClient db;
    private final CatalogService catalog;
    private final IdentityStore identity;
    private final StockService stock;
    private final AssemblyService assembly;
    private final NotificationWriter notifications;
    public OrderService(JdbcClient db, CatalogService catalog, IdentityStore identity, StockService stock,AssemblyService assembly,NotificationWriter notifications) {
        this.db=db; this.catalog=catalog; this.identity=identity; this.stock=stock; this.assembly=assembly; this.notifications=notifications;
    }
    private static void rule(boolean valid, String code) {
        if (!valid) throw new ApiException(409, code, "The requested order change is not allowed.");
    }
    private static ApiException missing() { return new ApiException(404, "NOT_FOUND", "Order not found."); }
    private void guard() {
        // Keep assignee eligibility and catalog state stable throughout creation or release.
        db.sql("SELECT id FROM security_guard WHERE id=1 FOR UPDATE").query(Integer.class).single();
        db.sql("SELECT id FROM catalog_guard WHERE id=1 FOR UPDATE").query(Integer.class).single();
    }
    private void audit(UUID actor, String action, UUID target) {
        db.sql("INSERT INTO audit_logs(id,actor_id,action,target_id) VALUES (:id,:actor,:action,:target)")
            .param("id", UUID.randomUUID()).param("actor", actor).param("action", action).param("target", target).update();
    }
    private boolean eligible(UUID id) {
        var permissions=identity.permissions(id);
        return permissions.contains("orders.view") || permissions.contains("orders.view-assigned");
    }
    public List<Person> responsiblePeople() {
        return db.sql("SELECT id,display_name,email FROM users WHERE active=true ORDER BY display_name,email")
            .query((r,n) -> new Person(r.getObject("id", UUID.class), r.getString("display_name"), r.getString("email")))
            .list().stream().filter(p -> eligible(p.id())).toList();
    }
    public List<Order> orders(UUID actor, boolean all) {
        return db.sql("SELECT id FROM manufacturing_orders WHERE (:all OR responsible_id=:actor) ORDER BY created_at DESC,id")
            .param("all", all).param("actor", actor).query(UUID.class).list().stream().map(this::load).toList();
    }
    public Order order(UUID id, UUID actor, boolean all) {
        var order=load(id);
        if (!all && !order.responsibleId().equals(actor)) throw missing();
        return order;
    }
    private static Instant instant(ResultSet r, String field) throws SQLException {
        var value=r.getTimestamp(field); return value == null ? null : value.toInstant();
    }
    private Order load(UUID id) {
        var items=db.sql("SELECT * FROM order_requirements WHERE order_id=:id ORDER BY position_code").param("id", id)
            .query((r,n) -> new Item(r.getObject("id", UUID.class), r.getString("position_code"), r.getObject("part_id", UUID.class),
                r.getString("part_code"), r.getString("part_name"), Shape.valueOf(r.getString("shape")), r.getBigDecimal("length_mm"),
                r.getBigDecimal("width_mm"), r.getBigDecimal("diameter_mm"), r.getInt("quantity"), r.getBigDecimal("expected_install_minutes"), r.getString("notes"))).list();
        return db.sql("""
            SELECT o.*,u.display_name responsible_name,m.configuration_version,
              (SELECT coalesce(sum(quantity),0) FROM order_requirements WHERE order_id=o.id) required_count,
              (SELECT count(*) FROM assembly_tasks t JOIN order_requirements r ON r.id=t.requirement_id WHERE r.order_id=o.id AND t.status='INSTALLED') installed_count
            FROM manufacturing_orders o JOIN users u ON u.id=o.responsible_id JOIN machines m ON m.id=o.machine_id WHERE o.id=:id
            """)
            .param("id", id).query((r,n) -> new Order(id,r.getString("code"),r.getString("title"),r.getObject("machine_id",UUID.class),
                r.getObject("bom_id",UUID.class),r.getObject("responsible_id",UUID.class),r.getString("responsible_name"),instant(r,"delivery_at"),
                r.getString("notes"),r.getString("status"),r.getString("machine_serial"),r.getString("machine_name"),r.getString("model_name"),
                r.getInt("bom_revision"),r.getLong("version"),instant(r,"created_at"),instant(r,"opened_at"),instant(r,"cancelled_at"),
                r.getString("cancellation_reason"),items,r.getLong("required_count"),r.getLong("installed_count"),instant(r,"assembly_completed_at"),r.getLong("configuration_version"),
                instant(r,"tested_at"),instant(r,"completed_at"),instant(r,"delivered_at"),r.getString("delivery_notes"),r.getObject("passed_trial_id",UUID.class),r.getBoolean("rework_required"))).optional().orElseThrow(OrderService::missing);
    }
    private Machine validate(UUID machineId, UUID bomId, UUID responsibleId, Instant deliveryAt) {
        rule(deliveryAt.isAfter(Instant.now()), "ORDER_DELIVERY_PAST");
        var machine=catalog.machines().stream().filter(m -> m.id().equals(machineId)).findFirst().orElseThrow(OrderService::missing);
        rule(machine.active(), "MACHINE_INACTIVE");
        var bom=catalog.bom(bomId);
        rule(machineId.equals(bom.machineId()) && bom.status().equals("PUBLISHED"), "ORDER_BOM_INVALID");
        rule(!bom.items().isEmpty(), "BOM_EMPTY");
        rule(db.sql("SELECT count(*) FROM bom_items i JOIN parts p ON p.id=i.part_id WHERE i.bom_id=:id AND NOT p.active")
            .param("id",bomId).query(Long.class).single()==0, "PART_INACTIVE");
        rule(db.sql("SELECT count(*) FROM users WHERE id=:id AND active=true").param("id",responsibleId).query(Long.class).single()==1
            && eligible(responsibleId), "ORDER_RESPONSIBLE_INVALID");
        return machine;
    }
    @Transactional
    public Order save(UUID id, Input p, UUID actor) {
        guard(); boolean creating=id==null; Order old=null;
        if (!creating) {
            old=load(id); rule(old.version()==p.version(), "STALE_VERSION"); rule(old.status().equals("DRAFT"), "ORDER_LOCKED");
            rule(old.code().equals(p.code().toUpperCase(Locale.ROOT)), "CODE_IMMUTABLE");
        }
        var machine=validate(p.machineId(),p.bomId(),p.responsibleId(),p.deliveryAt()); var bom=catalog.bom(p.bomId());
        if (creating) {
            id=UUID.randomUUID();
            db.sql("""
                INSERT INTO manufacturing_orders(id,code,title,machine_id,bom_id,responsible_id,delivery_at,notes,status,
                  machine_serial,machine_name,model_name,bom_revision,created_by)
                VALUES (:id,:code,:title,:machine,:bom,:person,:due,:notes,'DRAFT',:serial,:name,:model,:revision,:actor)
                """).param("id",id).param("code",p.code().toUpperCase(Locale.ROOT)).param("title",p.title().trim())
                .param("machine",p.machineId()).param("bom",p.bomId()).param("person",p.responsibleId()).param("due",Timestamp.from(p.deliveryAt()))
                .param("notes",p.notes().trim()).param("serial",machine.serialNumber()).param("name",machine.name()).param("model",machine.modelName())
                .param("revision",bom.revision()).param("actor",actor).update();
        } else {
            db.sql("""
                UPDATE manufacturing_orders SET title=:title,machine_id=:machine,bom_id=:bom,responsible_id=:person,delivery_at=:due,
                  notes=:notes,machine_serial=:serial,machine_name=:name,model_name=:model,bom_revision=:revision,version=version+1 WHERE id=:id
                """).param("id",id).param("title",p.title().trim()).param("machine",p.machineId()).param("bom",p.bomId())
                .param("person",p.responsibleId()).param("due",Timestamp.from(p.deliveryAt())).param("notes",p.notes().trim())
                .param("serial",machine.serialNumber()).param("name",machine.name()).param("model",machine.modelName()).param("revision",bom.revision()).update();
        }
        if (creating || !old.bomId().equals(p.bomId())) {
            db.sql("DELETE FROM order_requirements WHERE order_id=:id").param("id",id).update();
            for (var item : bom.items()) db.sql("""
                INSERT INTO order_requirements(id,order_id,source_item_id,part_id,position_code,part_code,part_name,shape,
                  length_mm,width_mm,diameter_mm,quantity,expected_install_minutes,notes)
                SELECT :id,:order,id,part_id,position_code,part_code,part_name,shape,length_mm,width_mm,diameter_mm,quantity,expected_install_minutes,notes
                FROM bom_items WHERE id=:item
                """).param("id",UUID.randomUUID()).param("order",id).param("item",item.id()).update();
        }
        audit(actor,creating ? "ORDER_CREATED" : "ORDER_UPDATED",id); return load(id);
    }
    @Transactional
    public Order release(UUID id, long version, UUID actor) {
        guard(); var old=load(id); rule(old.version()==version,"STALE_VERSION"); rule(old.status().equals("DRAFT"),"ORDER_LOCKED");
        validate(old.machineId(),old.bomId(),old.responsibleId(),old.deliveryAt());
        rule(!old.items().isEmpty(),"BOM_EMPTY");
        db.sql("UPDATE manufacturing_orders SET status='OPEN',opened_at=CURRENT_TIMESTAMP,version=version+1 WHERE id=:id").param("id",id).update();
        stock.allocateOnRelease(id,actor);
        assembly.createTasks(id);
        notifications.emit(NotificationWriter.Kind.ORDER_RELEASED,id,null,"release",actor);
        if(stock.orderStock(id,actor,true).requirements().stream().anyMatch(r->r.missing()>0)) notifications.emit(NotificationWriter.Kind.ORDER_SHORTAGE,id,null,"release",actor);
        audit(actor,"ORDER_OPENED",id); return load(id);
    }
    @Transactional
    public Order cancel(UUID id, CancelInput p, UUID actor) {
        guard(); var old=load(id); rule(old.version()==p.version(),"STALE_VERSION"); rule(!Set.of("CANCELLED","COMPLETED","TESTING").contains(old.status()),"ORDER_LOCKED");
        stock.beforeCancel(id,actor);
        db.sql("UPDATE manufacturing_orders SET status='CANCELLED',cancelled_at=CURRENT_TIMESTAMP,cancellation_reason=:reason,version=version+1 WHERE id=:id")
            .param("id",id).param("reason",p.reason().trim()).update();
        audit(actor,"ORDER_CANCELLED",id); return load(id);
    }
}

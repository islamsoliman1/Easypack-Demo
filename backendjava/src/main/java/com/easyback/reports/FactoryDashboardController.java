package com.easyback.reports;

import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/factory-dashboard")
public class FactoryDashboardController {
    public record State(String status,long count) {}
    public record Overview(Map<String,Long> metrics,List<State> states) {}
    private final JdbcClient db;
    public FactoryDashboardController(JdbcClient db) { this.db=db; }
    @GetMapping @PreAuthorize("hasAuthority('reports.view')")
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Overview overview() {
        var metrics=new LinkedHashMap<String,Long>();
        metrics.put("machines",count("SELECT count(*) FROM machines WHERE active"));
        metrics.put("orders",count("SELECT count(*) FROM manufacturing_orders"));
        metrics.put("active",count("SELECT count(*) FROM manufacturing_orders WHERE status NOT IN('DRAFT','CANCELLED','COMPLETED')"));
        metrics.put("completed",count("SELECT count(*) FROM manufacturing_orders WHERE status='COMPLETED'"));
        metrics.put("delivered",count("SELECT count(*) FROM manufacturing_orders WHERE delivered_at IS NOT NULL"));
        metrics.put("late",count("SELECT count(*) FROM manufacturing_orders WHERE status NOT IN('DRAFT','CANCELLED') AND delivered_at IS NULL AND delivery_at<CURRENT_TIMESTAMP"));
        metrics.put("defects",count("SELECT count(*) FROM defect_cases WHERE status NOT IN('RETURNED','SCRAPPED')"));
        metrics.put("available",count("SELECT count(*) FROM stock_pieces WHERE status='AVAILABLE'"));
        metrics.put("quarantined",count("SELECT count(*) FROM stock_pieces WHERE status='QUARANTINED'"));
        metrics.put("running",count("SELECT count(*) FROM work_sessions WHERE ended_at IS NULL"));
        metrics.put("unassigned",count("SELECT count(*) FROM assembly_tasks t JOIN order_requirements r ON r.id=t.requirement_id JOIN manufacturing_orders o ON o.id=r.order_id WHERE t.worker_id IS NULL AND o.status NOT IN('DRAFT','CANCELLED','COMPLETED')"));
        metrics.put("missing",count("""
            SELECT coalesce(sum(greatest(0,r.quantity-(SELECT count(*) FROM stock_pieces s
                WHERE s.requirement_id=r.id AND s.status IN('RESERVED','ISSUED','INSTALLED')))),0)
            FROM order_requirements r JOIN manufacturing_orders o ON o.id=r.order_id
            WHERE o.status NOT IN('DRAFT','CANCELLED','COMPLETED')
            """));
        metrics.put("required",count("SELECT coalesce(sum(r.quantity),0) FROM order_requirements r JOIN manufacturing_orders o ON o.id=r.order_id WHERE o.status NOT IN('DRAFT','CANCELLED','COMPLETED')"));
        metrics.put("installed",count("SELECT count(*) FROM assembly_tasks t JOIN order_requirements r ON r.id=t.requirement_id JOIN manufacturing_orders o ON o.id=r.order_id WHERE t.status='INSTALLED' AND o.status NOT IN('DRAFT','CANCELLED','COMPLETED')"));
        return new Overview(metrics,db.sql("SELECT status,count(*) amount FROM manufacturing_orders GROUP BY status ORDER BY status")
            .query((r,n)->new State(r.getString("status"),r.getLong("amount"))).list());
    }
    private long count(String sql) { return db.sql(sql).query(Long.class).single(); }
}

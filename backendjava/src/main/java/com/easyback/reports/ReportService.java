package com.easyback.reports;

import com.easyback.identity.ApiException;
import com.easyback.time.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class ReportService {
    public record Row(UUID id,String code,String title,String machineSerial,String status,Instant deliveryAt,Instant assemblyCompletedAt,Instant completedAt,Instant deliveredAt,
        long required,long installed,long missing,long defects,BigDecimal expectedMinutes,WorkData.Total time,long untrackedTasks,boolean assemblyLate,boolean completionLate,boolean deliveryLate) {}
    public record Summary(LocalDate from,LocalDate to,long total,long active,long ready,long rework,long completed,long delivered,long completionLate,long deliveryLate,long missing,long defects,List<Row> orders) {}
    private final JdbcClient db; private final WorkService work; private final Clock clock;
    public ReportService(JdbcClient db,WorkService work,Clock clock) { this.db=db; this.work=work; this.clock=clock; }
    private Instant instant(ResultSet r,String column) throws SQLException { var t=r.getTimestamp(column); return t==null?null:t.toInstant(); }
    private boolean late(Instant actual,Instant due) { return (actual==null?clock.instant():actual).isAfter(due); }
    public record Filter(String q,String status,boolean late,boolean missing) {
        public boolean matches(Row row) {
            return (row.code()+" "+com.easyback.localization.LocalizedText.searchText(row.title())+" "+row.machineSerial()).toLowerCase(Locale.ROOT).contains(q.trim().toLowerCase(Locale.ROOT))
                && (status.isBlank() || status.equals("all") || row.status().equals(status))
                && (!late || row.completionLate()) && (!missing || row.missing()>0);
        }
    }
    public Summary overview(LocalDate from,LocalDate to) {
        return overview(from,to,new Filter("","",false,false));
    }
    public Summary overview(LocalDate from,LocalDate to,Filter filter) {
        if(to.isBefore(from) || to.isAfter(from.plusDays(366))) throw new ApiException(400,"REPORT_RANGE","Report range must be at most 367 calendar days.");
        var rows=db.sql("""
            SELECT o.*,
              (SELECT coalesce(sum(r.quantity),0) FROM order_requirements r WHERE r.order_id=o.id) required,
              (SELECT count(*) FROM assembly_tasks t JOIN order_requirements r ON r.id=t.requirement_id WHERE r.order_id=o.id AND t.status='INSTALLED') installed,
              (SELECT coalesce(sum(greatest(0,r.quantity-(SELECT count(*) FROM stock_pieces s WHERE s.requirement_id=r.id AND s.status IN('RESERVED','ISSUED','INSTALLED')))),0) FROM order_requirements r WHERE r.order_id=o.id) missing,
              (SELECT count(*) FROM defect_cases d JOIN assembly_tasks t ON t.id=d.task_id JOIN order_requirements r ON r.id=t.requirement_id WHERE r.order_id=o.id AND d.status NOT IN('RETURNED','SCRAPPED')) defects,
              (SELECT coalesce(sum(r.quantity*r.expected_install_minutes),0) FROM order_requirements r WHERE r.order_id=o.id) expected_minutes,
              (SELECT count(*) FROM assembly_tasks t JOIN order_requirements r ON r.id=t.requirement_id WHERE r.order_id=o.id AND (t.incomplete_time_history OR (t.started_at IS NOT NULL AND NOT EXISTS(SELECT 1 FROM work_sessions w WHERE w.task_id=t.id)))) untracked_tasks
            FROM manufacturing_orders o WHERE o.delivery_at>=:from AND o.delivery_at<:to ORDER BY o.delivery_at,o.code
            """).param("from",Timestamp.from(from.atStartOfDay(WorkMath.ZONE).toInstant())).param("to",Timestamp.from(to.plusDays(1).atStartOfDay(WorkMath.ZONE).toInstant()))
            .query((r,n)->{
                UUID id=r.getObject("id",UUID.class); var due=instant(r,"delivery_at"); var assembly=instant(r,"assembly_completed_at"); var completed=instant(r,"completed_at"); var delivered=instant(r,"delivered_at"); String status=r.getString("status"); boolean cancelled=status.equals("CANCELLED");
                long untracked=r.getLong("untracked_tasks"); var time=work.orderTotal(id);
                if(untracked>0) time=new WorkData.Total(time.grossSeconds(),null,time.unconfirmedSessions());
                return new Row(id,r.getString("code"),r.getString("title"),r.getString("machine_serial"),status,due,assembly,completed,delivered,r.getLong("required"),r.getLong("installed"),cancelled?0:r.getLong("missing"),cancelled?0:r.getLong("defects"),r.getBigDecimal("expected_minutes"),time,untracked,!cancelled && late(assembly,due),!cancelled && late(completed,due),!cancelled && late(delivered,due));
            }).list().stream().filter(filter::matches).toList();
        return new Summary(from,to,rows.size(),rows.stream().filter(r->!Set.of("DRAFT","CANCELLED","COMPLETED").contains(r.status())).count(),rows.stream().filter(r->r.status().equals("AWAITING_TESTING")).count(),rows.stream().filter(r->r.status().equals("REWORK")).count(),rows.stream().filter(r->r.status().equals("COMPLETED")).count(),rows.stream().filter(r->r.deliveredAt()!=null).count(),rows.stream().filter(Row::completionLate).count(),rows.stream().filter(Row::deliveryLate).count(),rows.stream().mapToLong(Row::missing).sum(),rows.stream().mapToLong(Row::defects).sum(),rows);
    }
    static String cell(Object value) {
        String text=value==null?"":value.toString();
        if(!text.isEmpty() && ("=+-@".indexOf(text.stripLeading().isEmpty()?' ':text.stripLeading().charAt(0))>=0 || text.charAt(0)=='\t' || text.charAt(0)=='\r')) text="'"+text;
        return "\""+text.replace("\"","\"\"")+"\"";
    }
    public String csv(LocalDate from,LocalDate to) {
        return csv(from,to,new Filter("","",false,false));
    }
    public String csv(LocalDate from,LocalDate to,Filter filter) {
        var out=new StringBuilder("\uFEFF"+com.easyback.localization.LocalizedText.message("csvHeaders", "")+"\r\n");
        for(var r:overview(from,to,filter).orders()) {
            Object[] values={r.code(),com.easyback.localization.LocalizedText.display(r.title()),r.machineSerial(),com.easyback.localization.LocalizedText.status(r.status()),r.deliveryAt(),r.assemblyCompletedAt(),r.completedAt(),r.deliveredAt(),r.required(),r.installed(),r.missing(),r.defects(),r.expectedMinutes(),r.time().grossSeconds(),r.time().netSeconds(),r.time().unconfirmedSessions(),r.untrackedTasks(),yesNo(r.assemblyLate()),yesNo(r.completionLate()),yesNo(r.deliveryLate())};
            out.append(String.join(",",Arrays.stream(values).map(ReportService::cell).toList())).append("\r\n");
        }
        return out.toString();
    }
    private static String yesNo(boolean value) { return com.easyback.localization.LocalizedText.message(value?"yes":"no",Boolean.toString(value)); }
}

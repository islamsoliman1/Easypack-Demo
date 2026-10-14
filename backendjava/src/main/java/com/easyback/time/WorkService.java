package com.easyback.time;

import static com.easyback.time.WorkData.*;
import com.easyback.time.WorkData.Calendar;
import com.easyback.identity.ApiException;
import java.sql.*;
import java.time.*;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class WorkService {
    private final JdbcClient db; private final Clock clock; private final JsonMapper json=JsonMapper.builder().build();
    public WorkService(JdbcClient db,Clock clock) { this.db=db; this.clock=clock; }
    private void guard() {
        db.sql("SELECT id FROM security_guard WHERE id=1 FOR UPDATE").query(Integer.class).single();
        db.sql("SELECT id FROM catalog_guard WHERE id=1 FOR UPDATE").query(Integer.class).single();
    }
    private static void rule(boolean ok,String code) { if(!ok) throw new ApiException(409,code,"Work calendar action is not allowed."); }
    private List<Break> breaks(String value) { return Arrays.asList(json.readValue(value,Break[].class)); }
    public LocalDate today() { return clock.instant().atZone(WorkMath.ZONE).toLocalDate(); }
    public Instant now() { return clock.instant(); }
    public Calendar calendar() { return db.sql("SELECT * FROM work_calendar WHERE id=1").query((r,n)->new Calendar(r.getLong("version"),Arrays.stream((Integer[])r.getArray("weekdays").getArray()).toList(),breaks(r.getString("breaks")),r.getBoolean("breaks_confirmed"))).single(); }
    @Transactional
    public Calendar save(CalendarInput p,UUID actor) {
        guard(); rule(calendar().version()==p.version(),"STALE_VERSION");
        rule(!p.weekdays().contains(5) && new HashSet<>(p.weekdays()).size()==p.weekdays().size() && WorkMath.validBreaks(p.breaks()),"SHIFT_INVALID");
        db.sql("UPDATE work_calendar SET weekdays=CAST(:days AS INTEGER[]),breaks=CAST(:breaks AS JSONB),breaks_confirmed=true,version=version+1 WHERE id=1")
            .param("days","{"+String.join(",",p.weekdays().stream().map(Object::toString).toList())+"}").param("breaks",json.writeValueAsString(p.breaks())).update();
        audit(actor,"SHIFT_CALENDAR_UPDATED",null); return calendar();
    }
    private Day map(ResultSet r) throws SQLException { return new Day(r.getDate("day").toLocalDate(),r.getBoolean("working"),r.getBoolean("extended"),breaks(r.getString("breaks")),r.getBoolean("breaks_confirmed"),r.getLong("version"),r.getString("reason")); }
    public Day day(LocalDate date) {
        return db.sql("SELECT * FROM work_days WHERE day=:day").param("day",date).query((r,n)->map(r)).optional().orElseGet(()->{
            var c=calendar(); return new Day(date,c.weekdays().contains(date.getDayOfWeek().getValue()),false,c.breaks(),c.breaksConfirmed(),0,"");
        });
    }
    public List<Day> days(LocalDate from,LocalDate to) {
        rule(!to.isBefore(from) && !to.isAfter(from.plusDays(92)),"SHIFT_RANGE");
        return from.datesUntil(to.plusDays(1)).map(this::day).toList();
    }
    private void snapshot(Day d) {
        db.sql("INSERT INTO work_days(day,working,extended,breaks,breaks_confirmed,version,reason) VALUES(:day,:working,:extended,CAST(:breaks AS JSONB),:confirmed,:version,:reason) ON CONFLICT DO NOTHING")
            .param("day",d.day()).param("working",d.working()).param("extended",d.extended()).param("breaks",json.writeValueAsString(d.breaks())).param("confirmed",d.breaksConfirmed()).param("version",d.version()).param("reason",d.reason()).update();
    }
    @Transactional
    public Day changeDay(LocalDate date,DayInput p,UUID actor,boolean manage,boolean extend) {
        guard(); rule(!date.isBefore(today()),"SHIFT_PAST"); var d=day(date); rule(d.version()==p.version(),"STALE_VERSION");
        rule(p.working() || !p.extended(),"SHIFT_INVALID");
        rule((d.working()==p.working() || manage) && (d.extended()==p.extended() || extend),"SHIFT_PERMISSION");
        boolean used=db.sql("SELECT count(*) FROM work_sessions WHERE day=:day AND NOT (COALESCE(reason,'')='OUTSIDE_SHIFT' AND ended_at=started_at)").param("day",date).query(Long.class).single()>0;
        rule(!used || (d.working()==p.working() && !d.extended() && p.extended()),"SHIFT_DAY_LOCKED");
        snapshot(d);
        // Late approval must not revive an expired timer or credit the intervening gap.
        if(!d.extended() && p.extended() && !clock.instant().isBefore(WorkMath.end(d))) {
            var expired=db.sql("SELECT task_id FROM work_sessions WHERE day=:day AND ended_at IS NULL").param("day",date).query(UUID.class).list();
            expired.forEach(id->close(id,"SHIFT_ENDED"));
        }
        db.sql("UPDATE work_days SET working=:working,extended=:extended,reason=:reason,updated_by=:actor,version=version+1 WHERE day=:day")
            .param("day",date).param("working",p.working()).param("extended",p.extended()).param("reason",p.reason().trim()).param("actor",actor).update();
        audit(actor,"SHIFT_DAY_UPDATED",null); return day(date);
    }
    public void audit(UUID actor,String action,UUID target) {
        db.sql("INSERT INTO audit_logs(id,actor_id,action,target_id) VALUES(:id,:actor,:action,:target)")
            .param("id",UUID.randomUUID()).param("actor",actor).param("action",action).param("target",target,Types.OTHER).update();
    }
    @Transactional
    public void start(UUID task,UUID worker,boolean resume) {
        guard(); Instant now=clock.instant(); var d=day(today()); snapshot(d);
        rememberIncompleteHistory(task);
        // Expired sessions stop at their own day's boundary before another task/day starts.
        var open=db.sql("SELECT id,task_id,day FROM work_sessions WHERE worker_id=:worker AND ended_at IS NULL").param("worker",worker)
            .query((r,n)->new Object[]{r.getObject("task_id",UUID.class),r.getDate("day").toLocalDate()}).list();
        for(var s:open) if(!now.isBefore(WorkMath.end(day((LocalDate)s[1])))) close((UUID)s[0],"SHIFT_ENDED");
        rule(db.sql("SELECT count(*) FROM work_sessions WHERE worker_id=:id AND ended_at IS NULL").param("id",worker).query(Long.class).single()==0,"TIME_ALREADY_RUNNING");
        boolean inside=d.working() && !now.isBefore(WorkMath.begin(d)) && now.isBefore(WorkMath.end(d));
        rule(!resume || inside,"TIME_OUTSIDE_SHIFT");
        db.sql("INSERT INTO work_sessions(id,task_id,worker_id,day,started_at,ended_at,reason) VALUES(:id,:task,:worker,:day,:now,:ended,:reason)")
            .param("id",UUID.randomUUID()).param("task",task).param("worker",worker).param("day",d.day()).param("now",Timestamp.from(now))
            .param("ended",inside?null:Timestamp.from(now),Types.TIMESTAMP).param("reason",inside?null:"OUTSIDE_SHIFT",Types.VARCHAR).update();
    }
    @Transactional
    public void close(UUID task,String reason) {
        guard(); Instant now=clock.instant();
        rememberIncompleteHistory(task);
        var sessions=db.sql("SELECT id,day,started_at FROM work_sessions WHERE task_id=:task AND ended_at IS NULL").param("task",task)
            .query((r,n)->new Object[]{r.getObject("id",UUID.class),r.getDate("day").toLocalDate(),r.getTimestamp("started_at").toInstant()}).list();
        for(var s:sessions) {
            var boundary=WorkMath.end(day((LocalDate)s[1])); var finish=now.isBefore(boundary)?now:boundary; var start=(Instant)s[2];
            if(finish.isBefore(start)) finish=start;
            db.sql("UPDATE work_sessions SET ended_at=:end,reason=:reason WHERE id=:id").param("id",s[0]).param("end",Timestamp.from(finish)).param("reason",reason.trim()).update();
        }
    }
    @Transactional
    public void rememberIncompleteHistory(UUID task) {
        db.sql("""
            UPDATE assembly_tasks t SET incomplete_time_history=true WHERE t.id=:id AND NOT t.incomplete_time_history
              AND NOT EXISTS(SELECT 1 FROM work_sessions w WHERE w.task_id=t.id)
              AND (t.started_at IS NOT NULL OR EXISTS(SELECT 1 FROM installations i WHERE i.task_id=t.id)
                OR EXISTS(SELECT 1 FROM assembly_events e WHERE e.task_id=t.id AND e.action IN('STARTED','INSTALLED','UNINSTALLED')))
            """).param("id",task).update();
    }
    public Detail detail(UUID task) {
        Instant now=clock.instant(); var sessions=db.sql("SELECT s.*,u.display_name FROM work_sessions s JOIN users u ON u.id=s.worker_id WHERE s.task_id=:id ORDER BY started_at,id")
            .param("id",task).query((r,n)->{
                Instant start=r.getTimestamp("started_at").toInstant(), finish=r.getTimestamp("ended_at")==null?null:r.getTimestamp("ended_at").toInstant();
                var d=day(r.getDate("day").toLocalDate()); var end=finish==null?now:finish;
                return new Session(r.getObject("id",UUID.class),r.getObject("worker_id",UUID.class),r.getString("display_name"),start,finish,r.getString("reason"),d.day(),finish==null && now.isBefore(WorkMath.end(d)),WorkMath.gross(d,start,end),WorkMath.net(d,start,end));
            }).list();
        int unknown=(int)sessions.stream().filter(s->s.netSeconds()==null).count();
        boolean incomplete=db.sql("SELECT incomplete_time_history OR (started_at IS NOT NULL AND NOT EXISTS(SELECT 1 FROM work_sessions w WHERE w.task_id=t.id)) FROM assembly_tasks t WHERE id=:id")
            .param("id",task).query(Boolean.class).single();
        return new Detail(new Total(sessions.stream().mapToLong(Session::grossSeconds).sum(),unknown>0 || incomplete?null:sessions.stream().mapToLong(s->s.netSeconds()).sum(),unknown),sessions);
    }
    public Total orderTotal(UUID order) {
        var totals=db.sql("SELECT t.id FROM assembly_tasks t JOIN order_requirements r ON r.id=t.requirement_id WHERE r.order_id=:id").param("id",order).query(UUID.class).list().stream().map(t->detail(t).total()).toList();
        int unknown=totals.stream().mapToInt(Total::unconfirmedSessions).sum();
        return new Total(totals.stream().mapToLong(Total::grossSeconds).sum(),totals.stream().anyMatch(t->t.netSeconds()==null)?null:totals.stream().mapToLong(t->t.netSeconds()).sum(),unknown);
    }
}

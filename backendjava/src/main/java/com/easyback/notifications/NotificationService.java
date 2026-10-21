package com.easyback.notifications;

import com.easyback.identity.ApiException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class NotificationService {
    private final JdbcClient db;
    private final Clock clock;
    public NotificationService(JdbcClient db,Clock clock) { this.db=db; this.clock=clock; }
    public record Item(UUID id,String type,String severity,String title,String body,Instant createdAt,Instant readAt,String target,String orderCode,boolean sample) {}
    public record Page(List<Item> items,String nextCursor) {}
    private static ApiException invalid() { return new ApiException(400,"INVALID_NOTIFICATION_QUERY","Invalid notification filter or cursor."); }
    public Page list(UUID user,String cursor,int limit,boolean unreadOnly,String severity) {
        if(limit<1 || limit>50 || severity!=null && !Set.of("INFO","WARNING","URGENT").contains(severity)) throw invalid();
        Instant time=null; UUID id=null;
        if(cursor!=null) try {
            if(cursor.length()>160) throw invalid();
            String[] parts=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\\|",-1);
            if(parts.length!=2) throw invalid(); time=Instant.parse(parts[0]); id=UUID.fromString(parts[1]);
        } catch(RuntimeException e) { throw invalid(); }
        var q=db.sql("SELECT * FROM visible_notifications WHERE user_id=:user"+(unreadOnly?" AND read_at IS NULL":"")
            +(severity!=null?" AND severity=:severity":"")+(time!=null?" AND (created_at,id)<(:time,:cursorId)":"")+" ORDER BY created_at DESC,id DESC LIMIT :limit")
            .param("user",user).param("limit",limit+1);
        if(severity!=null) q.param("severity",severity);
        if(time!=null) q.param("time",Timestamp.from(time)).param("cursorId",id);
        var rows=q.query((r,n)->new Item(r.getObject("id",UUID.class),r.getString("type"),r.getString("severity"),r.getString("title"),r.getString("body"),
            r.getTimestamp("created_at").toInstant(),r.getTimestamp("read_at")==null?null:r.getTimestamp("read_at").toInstant(),target(r.getString("scope_type"),r.getObject("scope_id",UUID.class)),r.getString("order_code"),r.getBoolean("sample"))).list();
        boolean more=rows.size()>limit; var items=rows.subList(0,Math.min(rows.size(),limit));
        var last=items.isEmpty()?null:items.getLast();
        return new Page(items,more?Base64.getUrlEncoder().withoutPadding().encodeToString((last.createdAt()+"|"+last.id()).getBytes(StandardCharsets.UTF_8)):null);
    }
    private static String target(String scope,UUID id) {
        return switch(scope) { case "TASK" -> "/tasks?task="+id; case "DEFECT" -> "/defects?case="+id; case "PREPARATION" -> "/preparations?preparation="+id; case "OPERATIONS" -> "/operations/"+id; default -> "/orders/"+id; };
    }
    public long unread(UUID user) { return db.sql("SELECT count(*) FROM visible_notifications WHERE user_id=:user AND read_at IS NULL").param("user",user).query(Long.class).single(); }
    @Transactional
    public void read(UUID user,UUID id) {
        int changed=db.sql("UPDATE notification_recipients r SET read_at=coalesce(r.read_at,:now) WHERE r.user_id=:user AND r.notification_id=:id AND EXISTS(SELECT 1 FROM visible_notifications v WHERE v.id=r.notification_id AND v.user_id=r.user_id)")
            .param("now",Timestamp.from(clock.instant())).param("user",user).param("id",id).update();
        if(changed==0) throw new ApiException(404,"NOT_FOUND","Notification not found.");
    }
    @Transactional
    public void readAll(UUID user) {
        db.sql("UPDATE notification_recipients r SET read_at=:now WHERE r.user_id=:user AND r.read_at IS NULL AND EXISTS(SELECT 1 FROM visible_notifications v WHERE v.id=r.notification_id AND v.user_id=r.user_id)")
            .param("now",Timestamp.from(clock.instant())).param("user",user).update();
    }
}

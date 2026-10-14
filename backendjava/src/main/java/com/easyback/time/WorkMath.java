package com.easyback.time;

import static com.easyback.time.WorkData.*;
import java.time.*;
import java.util.*;

public final class WorkMath {
    public static final ZoneId ZONE=ZoneId.of("Africa/Cairo");
    private WorkMath() {}
    public static Instant begin(Day day) { return day.day().atTime(8,0).atZone(ZONE).toInstant(); }
    public static Instant end(Day day) { return day.day().atTime(day.extended()?20:17,0).atZone(ZONE).toInstant(); }
    public static long overlap(Instant start,Instant finish,Instant low,Instant high) {
        var a=start.isAfter(low)?start:low; var b=finish.isBefore(high)?finish:high;
        return b.isAfter(a)?Duration.between(a,b).getSeconds():0;
    }
    public static long gross(Day day,Instant start,Instant finish) { return day.working()?overlap(start,finish,begin(day),end(day)):0; }
    public static Long net(Day day,Instant start,Instant finish) {
        if(!day.breaksConfirmed()) return null;
        long seconds=gross(day,start,finish);
        if(day.working()) for(var b:day.breaks()) seconds-=overlap(start,finish,day.day().atTime(b.start()).atZone(ZONE).toInstant(),day.day().atTime(b.end()).atZone(ZONE).toInstant());
        return Math.max(0,seconds);
    }
    public static boolean validBreaks(List<Break> breaks) {
        var sorted=breaks.stream().sorted(Comparator.comparing(Break::start)).toList(); LocalTime previous=LocalTime.of(8,0);
        for(var b:sorted) {
            if(b.start().isBefore(previous) || !b.end().isAfter(b.start()) || b.end().isAfter(LocalTime.of(17,0))) return false;
            previous=b.end();
        }
        return true;
    }
}

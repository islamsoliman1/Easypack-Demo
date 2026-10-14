package com.easyback.time;

import static com.easyback.time.WorkData.*;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkMathTest {
    Day day(boolean working,boolean extended,boolean confirmed,List<Break> breaks) { return new Day(LocalDate.of(2026,10,12),working,extended,breaks,confirmed,0,""); }
    Instant at(int hour,int minute) { return LocalDate.of(2026,10,12).atTime(hour,minute).atZone(WorkMath.ZONE).toInstant(); }
    @Test void clampsToNormalShiftWithoutOvernightCredit() { var d=day(true,false,true,List.of()); assertEquals(9*3600,WorkMath.gross(d,at(6,0),at(23,0))); assertEquals(0,WorkMath.gross(d,at(18,0),at(23,0))); }
    @Test void extensionAddsExactlyThreeHours() { var d=day(true,true,true,List.of()); assertEquals(12*3600,WorkMath.gross(d,at(6,0),at(23,0))); }
    @Test void netIsUnknownUntilExplicitApproval() { assertNull(WorkMath.net(day(true,false,false,List.of()),at(8,0),at(17,0))); }
    @Test void breaksOnlyDeductOverlap() { var d=day(true,false,true,List.of(new Break(LocalTime.NOON,LocalTime.of(12,30)))); assertEquals(45*60,WorkMath.net(d,at(12,15),at(13,15))); }
    @Test void restDaysNeverAccrueWork() { var d=day(false,false,true,List.of()); assertEquals(0,WorkMath.net(d,at(8,0),at(17,0))); }
    @Test void rejectsOverlappingAndOutOfShiftBreaks() { assertFalse(WorkMath.validBreaks(List.of(new Break(LocalTime.of(7,0),LocalTime.of(8,0))))); assertFalse(WorkMath.validBreaks(List.of(new Break(LocalTime.NOON,LocalTime.of(13,0)),new Break(LocalTime.of(12,30),LocalTime.of(14,0))))); assertTrue(WorkMath.validBreaks(List.of())); }
    @Test void cairoBoundaryTracksSeasonalOffset() { var summer=new Day(LocalDate.of(2026,7,1),true,false,List.of(),true,0,""); var winter=new Day(LocalDate.of(2026,1,1),true,false,List.of(),true,0,""); assertEquals(Instant.parse("2026-07-01T05:00:00Z"),WorkMath.begin(summer)); assertEquals(Instant.parse("2026-01-01T06:00:00Z"),WorkMath.begin(winter)); }
}

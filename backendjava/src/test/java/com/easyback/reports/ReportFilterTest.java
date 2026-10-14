package com.easyback.reports;

import com.easyback.time.WorkData;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReportFilterTest {
    private ReportService.Row row(String code,String title,String status,boolean late,long missing) {
        return new ReportService.Row(UUID.randomUUID(),code,title,"SERIAL-1",status,Instant.EPOCH,null,null,null,
            4,1,missing,0,BigDecimal.TEN,new WorkData.Total(0,0L,0),0,false,late,false);
    }
    @Test void mixedRowsUseConjunctiveSearchStatusLateAndMissing() {
        var wanted=row("ORD-A","Pump Assembly","REWORK",true,2);
        var rows=List.of(wanted,row("ORD-B","Pump Assembly","COMPLETED",false,0),row("ORD-C","Valve","REWORK",true,2));
        var filter=new ReportService.Filter("  pUmP  ","REWORK",true,true);
        assertEquals(List.of(wanted),rows.stream().filter(filter::matches).toList());
        assertTrue(new ReportService.Filter("serial-1","",false,false).matches(wanted));
        assertTrue(new ReportService.Filter("ord-a","all",false,false).matches(wanted));
    }
    @Test void zeroMatchesNeverFallBackToAllRows() {
        var rows=List.of(row("A","Pump","OPEN",false,0),row("B","Valve","REWORK",true,1));
        assertEquals(0,rows.stream().filter(new ReportService.Filter("absent","",false,false)::matches).count());
        assertEquals(0,rows.stream().filter(new ReportService.Filter("Pump","",true,true)::matches).count());
    }
    @Test void csvEscapingStillQuotesAndNeutralizesSpreadsheetFormulas() {
        assertEquals("\"'=SUM(1,2)\"",ReportService.cell("=SUM(1,2)"));
        assertEquals("\"a,\"\"b\"\"\nline\"",ReportService.cell("a,\"b\"\nline"));
        assertEquals("\"\"",ReportService.cell(null));
    }
}

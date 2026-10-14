package com.easyback.reports;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/reports")
@PreAuthorize("hasAuthority('reports.view')")
public class ReportController {
    private final ReportService service;
    public ReportController(ReportService service) { this.service=service; }
    @GetMapping public ReportService.Summary overview(@RequestParam LocalDate from,@RequestParam LocalDate to,
        @RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String status,
        @RequestParam(defaultValue="false") boolean late,@RequestParam(defaultValue="false") boolean missing) {
        return service.overview(from,to,new ReportService.Filter(q,status,late,missing));
    }
    @GetMapping("/export") @PreAuthorize("hasAuthority('reports.view') and hasAuthority('reports.export')")
    public ResponseEntity<byte[]> export(@RequestParam LocalDate from,@RequestParam LocalDate to,
        @RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String status,
        @RequestParam(defaultValue="false") boolean late,@RequestParam(defaultValue="false") boolean missing) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
            .header("X-Content-Type-Options","nosniff").header("Content-Disposition","attachment; filename=easyback-report.csv").body(service.csv(from,to,new ReportService.Filter(q,status,late,missing)).getBytes(StandardCharsets.UTF_8));
    }
}

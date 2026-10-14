package com.easyback.time;

import static com.easyback.time.WorkData.*;
import com.easyback.time.WorkData.Calendar;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/shifts")
@PreAuthorize("hasAuthority('shifts.view')")
public class WorkController {
    private final WorkService service;
    public WorkController(WorkService service) { this.service=service; }
    private boolean has(Authentication a,String p) { return a.getAuthorities().stream().anyMatch(v->v.getAuthority().equals(p)); }
    @GetMapping public Calendar calendar() { return service.calendar(); }
    @PutMapping @PreAuthorize("hasAuthority('shifts.view') and hasAuthority('shifts.manage')")
    public Calendar save(@Valid @RequestBody CalendarInput p,Authentication a) { return service.save(p,UUID.fromString(a.getName())); }
    @GetMapping("/days") public List<Day> days(@RequestParam LocalDate from,@RequestParam LocalDate to) { return service.days(from,to); }
    @PutMapping("/days/{date}") @PreAuthorize("hasAuthority('shifts.view') and (hasAuthority('shifts.manage') or hasAuthority('shifts.extend'))")
    public Day change(@PathVariable LocalDate date,@Valid @RequestBody DayInput p,Authentication a) { return service.changeDay(date,p,UUID.fromString(a.getName()),has(a,"shifts.manage"),has(a,"shifts.extend")); }
}

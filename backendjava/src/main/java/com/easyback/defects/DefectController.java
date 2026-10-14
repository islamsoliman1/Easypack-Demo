package com.easyback.defects;

import static com.easyback.defects.DefectData.*;
import com.easyback.assembly.AssemblyData.Removal;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class DefectController {
    private final DefectService service;
    public DefectController(DefectService service) { this.service=service; }
    private static UUID actor(Authentication a) { return UUID.fromString(a.getName()); }
    private static boolean has(Authentication a,String p) { return a.getAuthorities().stream().anyMatch(v->v.getAuthority().equals(p)); }
    private static boolean all(Authentication a) { return has(a,"defects.view") && has(a,"orders.view"); }
    private static final String GLOBAL="hasAuthority('defects.view') and hasAuthority('orders.view')";
    private static final String VIEW="("+GLOBAL+") or hasAuthority('tasks.view-assigned') or (hasAuthority('tasks.view') and hasAuthority('orders.view'))";
    @GetMapping("/defects") @PreAuthorize(VIEW)
    public List<Case> list(Authentication a) { return service.list(actor(a),all(a)); }
    @GetMapping("/defects/{id}") @PreAuthorize(VIEW)
    public Detail detail(@PathVariable UUID id,Authentication a) { return service.detail(id,actor(a),all(a)); }
    @PostMapping("/assembly/tasks/{id}/reject") @PreAuthorize("hasAuthority('tasks.reject') and (hasAuthority('tasks.view-assigned') or (hasAuthority('tasks.view') and hasAuthority('orders.view')))")
    public Detail report(@PathVariable UUID id,@Valid @RequestBody Removal p,Authentication a) { return service.report(id,p,actor(a),has(a,"tasks.view") && has(a,"orders.view")); }
    @PostMapping("/defects/{id}/receive") @PreAuthorize(GLOBAL+" and hasAuthority('stock.view') and hasAuthority('stock.return')")
    public Detail receive(@PathVariable UUID id,@Valid @RequestBody Receipt p,Authentication a) { return service.receive(id,p,actor(a)); }
    @PostMapping("/defects/{id}/repair-start") @PreAuthorize(GLOBAL+" and hasAuthority('defects.manage')")
    public Detail start(@PathVariable UUID id,@Valid @RequestBody Action p,Authentication a) { return service.start(id,p,actor(a)); }
    @PostMapping("/defects/{id}/repair-finish") @PreAuthorize(GLOBAL+" and hasAuthority('defects.manage')")
    public Detail finish(@PathVariable UUID id,@Valid @RequestBody Action p,Authentication a) { return service.finish(id,p,actor(a)); }
    @PostMapping("/defects/{id}/accept") @PreAuthorize(GLOBAL+" and hasAuthority('stock.view') and hasAuthority('stock.receive')")
    public Detail accept(@PathVariable UUID id,@Valid @RequestBody Receipt p,Authentication a) { return service.accept(id,p,actor(a)); }
    @PostMapping("/defects/{id}/scrap") @PreAuthorize(GLOBAL+" and hasAuthority('defects.scrap')")
    public Detail scrap(@PathVariable UUID id,@Valid @RequestBody Action p,Authentication a) { return service.scrap(id,p,actor(a)); }
}

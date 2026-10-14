package com.easyback.assembly;

import static com.easyback.assembly.AssemblyData.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class AssemblyController {
    private final AssemblyService service;
    public AssemblyController(AssemblyService service) { this.service=service; }
    private static UUID actor(Authentication a) { return UUID.fromString(a.getName()); }
    private static boolean has(Authentication a,String p) { return a.getAuthorities().stream().anyMatch(v->v.getAuthority().equals(p)); }
    private static boolean all(Authentication a) { return has(a,"tasks.view") && has(a,"orders.view"); }
    private static final String VIEW="(hasAuthority('tasks.view') and hasAuthority('orders.view')) or hasAuthority('tasks.view-assigned')";
    @GetMapping("/assembly/tasks") @PreAuthorize(VIEW)
    public List<Task> tasks(@RequestParam(required=false) UUID orderId,Authentication a) { return service.tasks(actor(a),all(a),orderId); }
    @GetMapping("/assembly/tasks/{id}") @PreAuthorize(VIEW)
    public Detail detail(@PathVariable UUID id,Authentication a) { return service.detail(id,actor(a),all(a)); }
    @GetMapping("/assembly/workers") @PreAuthorize("hasAuthority('tasks.view') and hasAuthority('orders.view') and hasAuthority('tasks.assign')")
    public List<Person> workers() { return service.workers(); }
    @PostMapping("/assembly/tasks/{id}/assign") @PreAuthorize("hasAuthority('tasks.view') and hasAuthority('orders.view') and hasAuthority('tasks.assign')")
    public Detail assign(@PathVariable UUID id,@Valid @RequestBody Assignment p,Authentication a) { return service.assign(id,p,actor(a)); }
    @PostMapping("/assembly/tasks/{id}/start") @PreAuthorize("("+VIEW+") and hasAuthority('tasks.work')")
    public Detail start(@PathVariable UUID id,@Valid @RequestBody Action p,Authentication a) { return service.start(id,p,actor(a),all(a)); }
    @PostMapping("/assembly/tasks/{id}/pause") @PreAuthorize("("+VIEW+") and hasAuthority('tasks.work')")
    public Detail pause(@PathVariable UUID id,@Valid @RequestBody Action p,Authentication a) { return service.timer(id,p,actor(a),all(a),false,false); }
    @PostMapping("/assembly/tasks/{id}/resume") @PreAuthorize("("+VIEW+") and hasAuthority('tasks.work')")
    public Detail resume(@PathVariable UUID id,@Valid @RequestBody Action p,Authentication a) { return service.timer(id,p,actor(a),all(a),true,false); }
    @PostMapping("/assembly/tasks/{id}/stop-time") @PreAuthorize("hasAuthority('tasks.view') and hasAuthority('orders.view') and hasAuthority('time.manage')")
    public Detail stop(@PathVariable UUID id,@Valid @RequestBody Action p,Authentication a) { return service.timer(id,p,actor(a),true,false,true); }
    @PostMapping("/assembly/tasks/{id}/install") @PreAuthorize("("+VIEW+") and hasAuthority('tasks.work')")
    public Detail install(@PathVariable UUID id,@Valid @RequestBody Action p,Authentication a) { return service.install(id,p,actor(a),all(a)); }
    @PostMapping("/assembly/tasks/{id}/uninstall") @PreAuthorize("("+VIEW+") and hasAuthority('tasks.uninstall')")
    public Detail uninstall(@PathVariable UUID id,@Valid @RequestBody Removal p,Authentication a) { return service.uninstall(id,p,actor(a),all(a)); }
    @GetMapping("/machines/{id}/assembly") @PreAuthorize("hasAuthority('machines.view') and hasAuthority('tasks.view') and hasAuthority('orders.view')")
    public MachineAssembly machine(@PathVariable UUID id) { return service.machine(id); }
}

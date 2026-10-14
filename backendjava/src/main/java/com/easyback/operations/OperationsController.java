package com.easyback.operations;

import static com.easyback.operations.OperationsData.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/operations")
public class OperationsController {
    private final OperationsService service;
    public OperationsController(OperationsService service) { this.service=service; }
    private static UUID actor(Authentication a) { return UUID.fromString(a.getName()); }
    private static final String VIEW="hasAuthority('operations.view')";
    @GetMapping("/orders") @PreAuthorize(VIEW)
    public List<Summary> orders() { return service.orders(); }
    @GetMapping("/orders/{id}") @PreAuthorize(VIEW)
    public Detail detail(@PathVariable UUID id) { return service.detail(id); }
    @PostMapping("/orders/{id}/start") @PreAuthorize(VIEW+" and hasAuthority('operations.start')")
    public Detail start(@PathVariable UUID id,@Valid @RequestBody Start p,Authentication a) { return service.start(id,p,actor(a)); }
    @PostMapping("/trials/{id}/finish") @PreAuthorize(VIEW+" and hasAuthority('operations.finish')")
    public Detail finish(@PathVariable UUID id,@Valid @RequestBody Finish p,Authentication a) { return service.finish(id,p,actor(a)); }
    @PostMapping("/trials/{id}/abort") @PreAuthorize(VIEW+" and hasAuthority('operations.finish')")
    public Detail abort(@PathVariable UUID id,@Valid @RequestBody Action p,Authentication a) { return service.abort(id,p,actor(a)); }
    @PostMapping("/trials/{id}/resolve") @PreAuthorize(VIEW+" and hasAuthority('operations.resolve')")
    public Detail resolve(@PathVariable UUID id,@Valid @RequestBody Resolution p,Authentication a) { return service.resolve(id,p,actor(a)); }
    @PostMapping("/orders/{id}/deliver") @PreAuthorize(VIEW+" and hasAuthority('operations.deliver')")
    public Detail deliver(@PathVariable UUID id,@Valid @RequestBody Action p,Authentication a) { return service.deliver(id,p,actor(a)); }
}

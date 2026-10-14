package com.easyback.warehouse;

import static com.easyback.warehouse.RawData.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/warehouse/raw")
public class RawController {
    private final RawService service;
    public RawController(RawService service) { this.service=service; }
    private static UUID actor(Authentication a) { return UUID.fromString(a.getName()); }
    private static final String VIEW="hasAuthority('stock.view')";
    private static final String ISSUE=VIEW+" and hasAuthority('orders.view') and hasAuthority('raw.issue')";
    private static final String CUT=VIEW+" and hasAuthority('orders.view') and hasAuthority('raw.cut')";
    @GetMapping("/units") @PreAuthorize(VIEW) public List<Unit> units() { return service.units(); }
    @GetMapping("/balances") @PreAuthorize(VIEW) public List<Balance> balances() { return service.balances(); }
    @GetMapping("/movements") @PreAuthorize(VIEW) public List<Movement> movements() { return service.movements(); }
    @GetMapping("/cuts") @PreAuthorize(VIEW) public List<Cut> cuts() { return service.cuts(); }
    @PostMapping("/receipts") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize(VIEW+" and hasAuthority('raw.receive') and hasAuthority('catalog.view')")
    public List<Unit> receive(@Valid @RequestBody Receipt p,Authentication a) { return service.receive(p,actor(a)); }
    @PostMapping("/units/{id}/issue") @PreAuthorize(ISSUE)
    public Unit issue(@PathVariable UUID id,@Valid @RequestBody Issue p,Authentication a) { return service.issue(id,p,actor(a)); }
    @PostMapping("/units/{id}/return") @PreAuthorize(ISSUE)
    public Unit returnUnit(@PathVariable UUID id,@Valid @RequestBody ReturnInput p,Authentication a) { return service.returnUnit(id,p,actor(a)); }
    @PostMapping("/units/{id}/cut-preview") @PreAuthorize(CUT)
    public Plan preview(@PathVariable UUID id,@Valid @RequestBody CutInput p) { return service.preview(id,p); }
    @PostMapping("/units/{id}/cut") @PreAuthorize(CUT)
    public Plan cut(@PathVariable UUID id,@Valid @RequestBody CutInput p,Authentication a) { return service.cut(id,p,actor(a)); }
}

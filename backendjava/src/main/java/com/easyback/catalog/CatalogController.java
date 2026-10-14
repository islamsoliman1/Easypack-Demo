package com.easyback.catalog;

import static com.easyback.catalog.CatalogData.*;
import com.easyback.identity.ApiException;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class CatalogController {
    private final CatalogService service;
    public CatalogController(CatalogService service) { this.service = service; }
    private static UUID actor(Authentication auth) { return UUID.fromString(auth.getName()); }
    private static final String CATALOG_WRITE = "hasAuthority('catalog.view') and hasAuthority('catalog.manage')";
    private static final String MACHINE_WRITE = "hasAuthority('machines.view') and hasAuthority('machines.manage') and hasAuthority('catalog.view')";
    private static final String BOM_READ = "hasAuthority('boms.view') and hasAuthority('catalog.view')";
    private static final String BOM_WRITE = BOM_READ + " and hasAuthority('boms.manage')";
    private void machineAccess(UUID bomId, Authentication auth, boolean write) {
        if (service.bom(bomId).machineId() == null) return;
        var authorities = auth.getAuthorities().stream().map(a -> a.getAuthority()).toList();
        if (!authorities.contains("machines.view") || (write && !authorities.contains("machines.manage")))
            throw new ApiException(403, "FORBIDDEN", "Machine permissions are required.");
    }
    @GetMapping("/machine-models") @PreAuthorize("hasAuthority('catalog.view')")
    public List<Model> models() { return service.models(); }
    @PostMapping("/machine-models") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize(CATALOG_WRITE)
    public Model createModel(@Valid @RequestBody ModelInput p, Authentication a) { return service.saveModel(null, p, actor(a)); }
    @PutMapping("/machine-models/{id}") @PreAuthorize(CATALOG_WRITE)
    public Model updateModel(@PathVariable UUID id, @Valid @RequestBody ModelInput p, Authentication a) { return service.saveModel(id, p, actor(a)); }
    @GetMapping("/parts") @PreAuthorize("hasAuthority('catalog.view')")
    public List<Part> parts() { return service.parts(); }
    @PostMapping("/parts") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize(CATALOG_WRITE)
    public Part createPart(@Valid @RequestBody PartInput p, Authentication a) { return service.savePart(null, p, actor(a)); }
    @PostMapping("/parts/with-model") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize(CATALOG_WRITE + " and " + BOM_WRITE)
    public Part createLinkedPart(@Valid @RequestBody LinkedPartInput p, Authentication a) { return service.createLinkedPart(p, actor(a)); }
    @PutMapping("/parts/{id}") @PreAuthorize(CATALOG_WRITE)
    public Part updatePart(@PathVariable UUID id, @Valid @RequestBody PartInput p, Authentication a) { return service.savePart(id, p, actor(a)); }
    @GetMapping("/machines") @PreAuthorize("hasAuthority('machines.view')")
    public List<Machine> machines() { return service.machines(); }
    @PostMapping("/machines") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize(MACHINE_WRITE)
    public Machine createMachine(@Valid @RequestBody MachineInput p, Authentication a) { return service.saveMachine(null, p, actor(a)); }
    @PutMapping("/machines/{id}") @PreAuthorize(MACHINE_WRITE)
    public Machine updateMachine(@PathVariable UUID id, @Valid @RequestBody MachineInput p, Authentication a) { return service.saveMachine(id, p, actor(a)); }
    @GetMapping("/machine-models/{id}/boms") @PreAuthorize(BOM_READ)
    public List<Bom> boms(@PathVariable UUID id) { return service.boms(id); }
    @GetMapping("/boms/{id}") @PreAuthorize(BOM_READ)
    public Bom bom(@PathVariable UUID id, Authentication a) { machineAccess(id, a, false); return service.bom(id); }
    @GetMapping("/machines/{id}/boms") @PreAuthorize(BOM_READ + " and hasAuthority('machines.view')")
    public List<Bom> machineBoms(@PathVariable UUID id) { return service.machineBoms(id); }
    @PostMapping("/machines/{id}/boms") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize(BOM_WRITE + " and " + MACHINE_WRITE)
    public Bom createMachineDraft(@PathVariable UUID id, @Valid @RequestBody DraftInput p, Authentication a) { return service.createMachineDraft(id, p, actor(a)); }
    @PostMapping("/machine-models/{id}/boms") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize(BOM_WRITE)
    public Bom createDraft(@PathVariable UUID id, @Valid @RequestBody DraftInput p, Authentication a) { return service.createDraft(id, p, actor(a)); }
    @PostMapping("/boms/{id}/items") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize(BOM_WRITE)
    public Bom createItem(@PathVariable UUID id, @Valid @RequestBody ItemInput p, Authentication a) { machineAccess(id, a, true); return service.saveItem(id, null, p, actor(a)); }
    @PutMapping("/boms/{id}/items/{item}") @PreAuthorize(BOM_WRITE)
    public Bom updateItem(@PathVariable UUID id, @PathVariable UUID item, @Valid @RequestBody ItemInput p, Authentication a) { machineAccess(id, a, true); return service.saveItem(id, item, p, actor(a)); }
    @DeleteMapping("/boms/{id}/items/{item}") @PreAuthorize(BOM_WRITE)
    public Bom removeItem(@PathVariable UUID id, @PathVariable UUID item, @Valid @RequestBody VersionInput p, Authentication a) { machineAccess(id, a, true); return service.removeItem(id, item, p.version(), actor(a)); }
    @PostMapping("/boms/{id}/publish") @PreAuthorize(BOM_WRITE)
    public Bom publish(@PathVariable UUID id, @Valid @RequestBody VersionInput p, Authentication a) { machineAccess(id, a, true); return service.publish(id, p.version(), actor(a)); }
}

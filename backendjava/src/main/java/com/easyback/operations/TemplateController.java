package com.easyback.operations;
import com.easyback.operations.OperationsData.CheckInput;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/operations/templates")
@PreAuthorize("hasAuthority('operations.view')")
public class TemplateController {
    private final TemplateService service;
    public TemplateController(TemplateService service) { this.service=service; }
    public record Input(@NotNull @PositiveOrZero Long version,@NotBlank @Size(max=120) String name,boolean active,
        @NotEmpty @Size(max=30) List<@NotNull @Valid CheckInput> checks) {}
    @GetMapping public List<TemplateService.Template> list() { return service.list(); }
    @PostMapping @PreAuthorize("hasAuthority('operations.view') and hasAuthority('operations.templates.manage')")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public TemplateService.Template create(@Valid @RequestBody Input p,Authentication a) { return service.save(null,p,UUID.fromString(a.getName())); }
    @PutMapping("/{id}") @PreAuthorize("hasAuthority('operations.view') and hasAuthority('operations.templates.manage')")
    public TemplateService.Template update(@PathVariable UUID id,@Valid @RequestBody Input p,Authentication a) { return service.save(id,p,UUID.fromString(a.getName())); }
}

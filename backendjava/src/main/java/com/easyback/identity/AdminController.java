package com.easyback.identity;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class AdminController {
    private final IdentityStore store;
    private final Administration service;
    public AdminController(IdentityStore store, Administration service) { this.store = store; this.service = service; }
    @GetMapping("/users") @PreAuthorize("hasAuthority('users.view')")
    public List<IdentityStore.UserView> users() { return store.users(); }
    @PostMapping("/users") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public IdentityStore.UserView create(@Valid @RequestBody Administration.UserInput input, Authentication auth) {
        return service.create(input, auth);
    }
    @PutMapping("/users/{id}")
    @PreAuthorize("hasAnyAuthority('users.update', 'users.deactivate', 'permissions.assign')")
    public IdentityStore.UserView update(@PathVariable UUID id, @Valid @RequestBody Administration.UserInput input, Authentication auth) {
        return service.update(id, input, auth);
    }
    @PostMapping("/users/{id}/password") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void password(@PathVariable UUID id, @Valid @RequestBody Administration.ResetPassword input, Authentication auth) {
        service.resetPassword(id, input, auth);
    }
    @GetMapping("/roles") @PreAuthorize("hasAnyAuthority('roles.view', 'permissions.assign')")
    public List<IdentityStore.RoleView> roles() { return store.roles(); }
    @GetMapping("/permissions") @PreAuthorize("hasAnyAuthority('roles.view', 'permissions.assign')")
    public List<String> permissions() { return Permissions.ALL; }
    @PostMapping("/roles") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public IdentityStore.RoleView createRole(@Valid @RequestBody Administration.RoleInput input, Authentication auth) {
        return service.saveRole(null, input, auth);
    }
    @PutMapping("/roles/{id}")
    public IdentityStore.RoleView updateRole(@PathVariable UUID id, @Valid @RequestBody Administration.RoleInput input, Authentication auth) {
        return service.saveRole(id, input, auth);
    }
}

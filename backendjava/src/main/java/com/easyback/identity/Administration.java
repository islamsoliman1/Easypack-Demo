package com.easyback.identity;

import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Administration {
    private final IdentityStore store;
    private final PasswordEncoder passwords;
    public Administration(IdentityStore store, PasswordEncoder passwords) { this.store = store; this.passwords = passwords; }
    public record UserInput(@NotBlank @Email @Size(max=254) String email,
        @NotBlank @Size(max=120) String displayName, String password, @NotNull Boolean active,
        @NotNull @Size(max=100) List<@NotNull UUID> roleIds,
        @NotNull Map<String, @NotNull Boolean> overrides, @NotNull Long version) {}
    public record RoleInput(@NotBlank @Size(max=80) String name, @NotNull @Size(max=240) String description,
        @NotNull Set<@NotNull String> permissions, @NotNull Long version) {}
    public record ResetPassword(@NotBlank String password, @NotNull Long version) {}
    static UUID actor(Authentication a) { return UUID.fromString(a.getName()); }
    static void require(Authentication a, String permission) {
        if (a.getAuthorities().stream().noneMatch(x -> x.getAuthority().equals(permission)))
            throw new AccessDeniedException("Permission required.");
    }
    static void passwordPolicy(String password) {
        if (password == null || password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new ApiException(400, "PASSWORD_POLICY", "Password must contain at least 12 characters and no more than 72 UTF-8 bytes.");
    }
    @Transactional
    public IdentityStore.UserView create(UserInput input, Authentication auth) {
        require(auth, "users.create"); store.guard(); passwordPolicy(input.password());
        if (!input.roleIds().isEmpty() || !input.overrides().isEmpty()) require(auth, "permissions.assign");
        var id = UUID.randomUUID();
        store.db().sql("""
            INSERT INTO users(id, email, display_name, password_hash, active)
            VALUES (:id, :email, :name, :hash, :active)
            """).param("id", id).param("email", normalizeEmail(input.email())).param("name", input.displayName().trim())
            .param("hash", passwords.encode(input.password())).param("active", input.active()).update();
        access(id, input.roleIds(), input.overrides()); store.audit(actor(auth), "USER_CREATED", id);
        return store.view(id);
    }
    @Transactional
    public IdentityStore.UserView update(UUID id, UserInput input, Authentication auth) {
        store.guard(); var current = store.account(id);
        if (current.version() != input.version()) conflict();
        if (!current.email().equals(normalizeEmail(input.email())))
            throw new ApiException(400, "EMAIL_IMMUTABLE", "Email cannot be changed in this release.");
        if (!current.displayName().equals(input.displayName().trim())) require(auth, "users.update");
        if (current.active() != input.active()) require(auth, "users.deactivate");
        boolean accessChanged = !new HashSet<>(store.roleIds(id)).equals(new HashSet<>(input.roleIds()))
            || !store.overrides(id).equals(input.overrides());
        if (accessChanged) require(auth, "permissions.assign");
        store.db().sql("UPDATE users SET display_name = :name, active = :active, version = version + 1 WHERE id = :id")
            .param("name", input.displayName().trim()).param("active", input.active()).param("id", id).update();
        if (accessChanged) access(id, input.roleIds(), input.overrides());
        if (!input.active()) store.revokeSessions(id);
        store.preserveAdmin(); store.audit(actor(auth), "USER_UPDATED", id);
        return store.view(id);
    }
    private void access(UUID user, List<UUID> roles, Map<String, Boolean> overrides) {
        Permissions.validate(overrides.keySet());
        for (var role : new HashSet<>(roles)) {
            if (store.db().sql("SELECT count(*) FROM roles WHERE id = :id").param("id", role).query(Long.class).single() == 0)
                throw new ApiException(400, "INVALID_ROLE", "Role does not exist.");
        }
        store.db().sql("DELETE FROM user_roles WHERE user_id = :id").param("id", user).update();
        store.db().sql("DELETE FROM user_permission_overrides WHERE user_id = :id").param("id", user).update();
        for (var role : new HashSet<>(roles)) store.db().sql("INSERT INTO user_roles(user_id, role_id) VALUES (:user, :role)")
            .param("user", user).param("role", role).update();
        overrides.forEach((permission, allowed) -> store.db().sql("INSERT INTO user_permission_overrides(user_id, permission, allowed) VALUES (:user, :p, :allowed)")
            .param("user", user).param("p", permission).param("allowed", allowed).update());
    }
    @Transactional
    public IdentityStore.RoleView saveRole(UUID id, RoleInput input, Authentication auth) {
        store.guard(); require(auth, id == null ? "roles.create" : "roles.update"); require(auth, "permissions.assign");
        Permissions.validate(input.permissions());
        if (id == null) {
            id = UUID.randomUUID();
            store.db().sql("INSERT INTO roles(id, name, description) VALUES (:id, :name, :description)")
                .param("id", id).param("name", input.name().trim()).param("description", input.description().trim()).update();
        } else {
            var version = store.db().sql("SELECT version FROM roles WHERE id = :id").param("id", id).query(Long.class).optional()
                .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "Role not found."));
            if (version != input.version().longValue()) conflict();
            store.db().sql("UPDATE roles SET name = :name, description = :description, version = version + 1 WHERE id = :id")
                .param("id", id).param("name", input.name().trim()).param("description", input.description().trim()).update();
            store.db().sql("DELETE FROM role_permissions WHERE role_id = :id").param("id", id).update();
        }
        for (var permission : input.permissions()) store.db().sql("INSERT INTO role_permissions(role_id, permission) VALUES (:id, :p)")
            .param("id", id).param("p", permission).update();
        store.preserveAdmin(); store.audit(actor(auth), "ROLE_SAVED", id);
        UUID roleId = id;
        return store.roles().stream().filter(r -> r.id().equals(roleId)).findFirst().orElseThrow();
    }
    @Transactional
    public void resetPassword(UUID id, ResetPassword input, Authentication auth) {
        require(auth, "users.reset-password"); store.guard(); passwordPolicy(input.password());
        if (store.account(id).version() != input.version()) conflict();
        store.db().sql("UPDATE users SET password_hash = :hash, must_change_password = true, version = version + 1 WHERE id = :id")
            .param("hash", passwords.encode(input.password())).param("id", id).update();
        store.revokeSessions(id); store.audit(actor(auth), "PASSWORD_RESET", id);
    }
    static String normalizeEmail(String email) { return email.trim().toLowerCase(Locale.ROOT); }
    static void conflict() { throw new ApiException(409, "STALE_VERSION", "Record changed. Reload before saving."); }
}

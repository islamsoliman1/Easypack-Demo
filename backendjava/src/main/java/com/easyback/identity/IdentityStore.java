package com.easyback.identity;

import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class IdentityStore {
    private final JdbcClient db;
    public IdentityStore(JdbcClient db) { this.db = db; }
    public JdbcClient db() { return db; }
    public record Account(UUID id, String email, String displayName, String passwordHash,
                          boolean active, boolean mustChangePassword, long version) {}
    public record UserView(UUID id, String email, String displayName, boolean active,
                           boolean mustChangePassword, long version, List<UUID> roleIds,
                           Map<String, Boolean> overrides, Set<String> permissions) {}
    public record RoleView(UUID id, String name, String description, long version, List<String> permissions) {}
    Optional<Account> findByEmail(String email) {
        return db.sql("SELECT * FROM users WHERE email = :email").param("email", email)
            .query((r, n) -> new Account(r.getObject("id", UUID.class), r.getString("email"),
                r.getString("display_name"), r.getString("password_hash"), r.getBoolean("active"),
                r.getBoolean("must_change_password"), r.getLong("version"))).optional();
    }
    Account account(UUID id) {
        return db.sql("SELECT * FROM users WHERE id = :id").param("id", id)
            .query((r, n) -> new Account(r.getObject("id", UUID.class), r.getString("email"),
                r.getString("display_name"), r.getString("password_hash"), r.getBoolean("active"),
                r.getBoolean("must_change_password"), r.getLong("version"))).optional()
            .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "User not found."));
    }
    List<UUID> roleIds(UUID id) {
        return db.sql("SELECT role_id FROM user_roles WHERE user_id = :id ORDER BY role_id")
            .param("id", id).query(UUID.class).list();
    }
    Map<String, Boolean> overrides(UUID id) {
        var result = new TreeMap<String, Boolean>();
        db.sql("SELECT permission, allowed FROM user_permission_overrides WHERE user_id = :id")
            .param("id", id).query((r, n) -> { result.put(r.getString(1), r.getBoolean(2)); return 0; }).list();
        return result;
    }
    public Set<String> permissions(UUID id) {
        var inherited = db.sql("""
            SELECT DISTINCT rp.permission FROM role_permissions rp
            JOIN user_roles ur ON ur.role_id = rp.role_id WHERE ur.user_id = :id
            """).param("id", id).query(String.class).list();
        return Permissions.effective(inherited, overrides(id));
    }
    UserView view(UUID id) {
        var a = account(id);
        return new UserView(id, a.email(), a.displayName(), a.active(), a.mustChangePassword(), a.version(),
            roleIds(id), overrides(id), permissions(id));
    }
    List<UserView> users() {
        return db.sql("SELECT id FROM users ORDER BY created_at, id").query(UUID.class).list().stream()
            .map(this::view).toList();
    }
    List<RoleView> roles() {
        return db.sql("SELECT * FROM roles ORDER BY name").query((r, n) -> new RoleView(
            r.getObject("id", UUID.class), r.getString("name"), r.getString("description"),
            r.getLong("version"), List.of())).list().stream().map(r -> new RoleView(r.id(), r.name(),
                r.description(), r.version(), db.sql("SELECT permission FROM role_permissions WHERE role_id = :id ORDER BY permission")
                .param("id", r.id()).query(String.class).list())).toList();
    }
    void guard() { db.sql("SELECT id FROM security_guard WHERE id = 1 FOR UPDATE").query(Integer.class).single(); }
    void audit(UUID actor, String action, UUID target) {
        db.sql("INSERT INTO audit_logs(id, actor_id, action, target_id) VALUES (:id, :actor, :action, :target)")
            .param("id", UUID.randomUUID()).param("actor", actor).param("action", action).param("target", target).update();
    }
    void preserveAdmin() {
        boolean exists = db.sql("SELECT id FROM users WHERE active = true").query(UUID.class).list().stream()
            .anyMatch(id -> permissions(id).containsAll(Permissions.ADMIN));
        if (!exists) throw new ApiException(409, "LAST_ADMIN", "At least one active full administrator is required.");
    }
    void revokeSessions(UUID user) {
        db.sql("UPDATE user_sessions SET revoked_at = CURRENT_TIMESTAMP WHERE user_id = :id AND revoked_at IS NULL")
            .param("id", user).update();
    }
}

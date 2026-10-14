package com.easyback.identity;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@org.springframework.context.annotation.Profile("!demo")
public class Bootstrap implements ApplicationRunner {
    private final IdentityStore store;
    private final PasswordEncoder encoder;
    private final String email, password;
    public Bootstrap(IdentityStore store, PasswordEncoder encoder,
        @Value("${easyback.bootstrap-email}") String email, @Value("${easyback.bootstrap-password}") String password) {
        this.store = store; this.encoder = encoder; this.email = email; this.password = password;
    }
    @Override @Transactional
    public void run(ApplicationArguments args) {
        store.guard();
        if (store.db().sql("SELECT count(*) FROM users").query(Long.class).single() != 0) return;
        Administration.passwordPolicy(password);
        if (email.isBlank() || !email.contains("@")) throw new IllegalStateException("A bootstrap email is required for an empty database.");
        UUID role = UUID.randomUUID(), user = UUID.randomUUID();
        store.db().sql("INSERT INTO roles(id, name, description) VALUES (:id, 'Administrator', 'Full security administration')")
            .param("id", role).update();
        for (var p : Permissions.ALL) store.db().sql("INSERT INTO role_permissions(role_id, permission) VALUES (:id, :p)")
            .param("id", role).param("p", p).update();
        for (var name : List.of("Manufacturing", "Warehouse", "Assembly", "Operations")) {
            store.db().sql("INSERT INTO roles(id, name, description) VALUES (:id, :name, 'Pilot department; operational permissions will follow')")
                .param("id", UUID.randomUUID()).param("name", name).update();
        }
        store.db().sql("INSERT INTO users(id, email, display_name, password_hash) VALUES (:id, :email, 'EasyBack Administrator', :hash)")
            .param("id", user).param("email", Administration.normalizeEmail(email)).param("hash", encoder.encode(password)).update();
        store.db().sql("INSERT INTO user_roles(user_id, role_id) VALUES (:user, :role)").param("user", user).param("role", role).update();
        store.audit(user, "BOOTSTRAP_CREATED", user);
    }
}

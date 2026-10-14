package com.easyback.identity;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PermissionsTest {
    @Test void explicitDenialWinsAndExplicitGrantWorks() {
        assertEquals(Set.of("users.view", "roles.view"), Permissions.effective(
            List.of("users.view", "users.update"), Map.of("users.update", false, "roles.view", true)));
    }
    @Test void rejectsUnknownPermissions() {
        assertThrows(ApiException.class, () -> Permissions.validate(List.of("made.up")));
    }
    @Test void enforcesBcryptByteLimit() {
        assertThrows(ApiException.class, () -> Administration.passwordPolicy("x".repeat(73)));
        assertThrows(ApiException.class, () -> Administration.passwordPolicy("short"));
        Administration.passwordPolicy("A-long-password-123");
    }
}

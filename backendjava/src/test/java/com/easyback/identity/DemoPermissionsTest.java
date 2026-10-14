package com.easyback.identity;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DemoPermissionsTest {
    @Test void visitorCanExerciseManufacturingWithoutChangingPublicIdentity() {
        var permissions=DemoBootstrap.visitorPermissions();
        assertTrue(permissions.containsAll(java.util.List.of("catalog.manage", "orders.release", "stock.issue", "tasks.work", "operations.finish", "reports.view", "users.view", "roles.view")));
        for (var permission:Permissions.ADMIN)
            if(!permission.equals("users.view") && !permission.equals("roles.view")) assertFalse(permissions.contains(permission),permission);
        assertFalse(permissions.contains("files.manage"));
    }
}

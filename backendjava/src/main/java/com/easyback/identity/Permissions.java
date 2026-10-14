package com.easyback.identity;

import java.util.*;

public final class Permissions {
    private Permissions() {}
    public static final List<String> ADMIN = List.of(
        "users.view", "users.create", "users.update", "users.deactivate", "users.reset-password",
        "roles.view", "roles.create", "roles.update", "permissions.assign");
    public static final List<String> ALL = java.util.stream.Stream.concat(ADMIN.stream(), List.of(
        "catalog.view", "catalog.manage", "machines.view", "machines.manage", "boms.view", "boms.manage",
        "orders.view", "orders.view-assigned", "orders.manage", "orders.release", "orders.cancel",
        "stock.view", "stock.receive", "stock.reserve", "stock.issue", "stock.return", "preparation.manage",
        "raw.receive", "raw.issue", "raw.cut", "tasks.view", "tasks.view-assigned", "tasks.assign", "tasks.work", "tasks.uninstall",
        "tasks.reject", "defects.view", "defects.manage", "defects.scrap",
        "operations.view", "operations.start", "operations.finish", "operations.resolve", "operations.deliver",
        "shifts.view", "shifts.manage", "shifts.extend", "time.manage", "files.manage", "reports.view", "reports.export", "operations.templates.manage").stream()).toList();
    public static Set<String> effective(Collection<String> inherited, Map<String, Boolean> overrides) {
        var result = new TreeSet<>(inherited);
        overrides.forEach((key, allowed) -> { if (allowed) result.add(key); else result.remove(key); });
        return Collections.unmodifiableSet(result);
    }
    public static void validate(Collection<String> values) {
        if (!ALL.containsAll(values)) throw new ApiException(400, "UNKNOWN_PERMISSION", "Unknown permission.");
    }
}

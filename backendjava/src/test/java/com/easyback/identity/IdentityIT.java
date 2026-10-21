package com.easyback.identity;

import java.net.*;
import java.net.http.*;
import java.util.*;
import java.time.Instant;
import java.time.Clock;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,properties="easyback.notifications.scheduler-enabled=false")
class IdentityIT {
    @DynamicPropertySource
    static void dedicatedDatabase(DynamicPropertyRegistry properties) {
        String url = System.getenv("DB_URL");
        if (url == null || !url.startsWith("jdbc:postgresql://") || !url.endsWith("/easyback_test"))
            throw new IllegalStateException("IdentityIT requires an explicit DB_URL pointing to the dedicated easyback_test database.");
        properties.add("spring.datasource.url", () -> url);
    }
    @LocalServerPort int port;
    @Autowired JdbcClient db;
    @Autowired Bootstrap bootstrap;
    @Autowired IdentityStore identityStore;
    @Autowired com.easyback.notifications.NotificationWriter notificationWriter;
    @Autowired com.easyback.notifications.DeliveryReminderEvaluator reminderEvaluator;
    @Autowired com.easyback.assembly.AssemblyService assemblyService;
    @Autowired com.easyback.files.FileService fileService;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    @MockitoBean Clock workClock;
    final JsonMapper json = JsonMapper.builder().build();
    static final String INITIAL = "TemporaryPassword123!", CHANGED = "ChangedPassword456!";
    Client admin;
    @BeforeEach void reset() throws Exception {
        when(workClock.instant()).thenReturn(Instant.parse("2026-10-12T09:00:00Z"));
        assertEquals("easyback_test", db.sql("SELECT current_database()").query(String.class).single(),
            "Refusing to reset a non-test database.");
        db.sql("TRUNCATE notification_recipients, notifications, work_sessions, work_days, resource_files, operations_templates, operations_events, operations_configuration, operations_checks, operations_trials, defect_events, defect_cases, assembly_events, installations, assembly_tasks, raw_cut_outputs, raw_cuts, raw_movements, raw_units, stock_movements, stock_pieces, preparation_requests, order_requirements, manufacturing_orders, bom_items, bom_versions, machines, parts, machine_models, audit_logs, refresh_tokens, user_sessions, user_permission_overrides, user_roles, role_permissions, roles, users CASCADE").update();
        db.sql("UPDATE work_calendar SET version=0,weekdays=ARRAY[1,2,3,4,6,7],breaks='[]',breaks_confirmed=false WHERE id=1").update();
        bootstrap.run(null);
        db.sql("UPDATE users SET must_change_password = false").update();
        admin = new Client(); admin.login("admin@test.easyback.local", "IntegrationAdmin123!");
    }
    class Client {
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();
        String access, csrf;
        URI uri(String path) { return URI.create("http://localhost:" + port + "/api/v1" + path); }
        HttpResponse<String> call(String method, String path, Object data, boolean sendCsrf) throws Exception {
            var request = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json");
            if (access != null) request.header("Authorization", "Bearer " + access);
            if (sendCsrf && csrf != null) request.header("X-XSRF-TOKEN", csrf);
            request.method(method, data == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(data)));
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
        JsonNode read(HttpResponse<String> response, int status) {
            assertEquals(status, response.statusCode(), response.body());
            return response.body().isBlank() ? json.nullNode() : json.readTree(response.body());
        }
        void csrf() throws Exception { csrf = read(call("GET", "/auth/csrf", null, false), 200).path("token").asText(); }
        void login(String email, String password) throws Exception {
            csrf(); access = read(call("POST", "/auth/login", Map.of("email", email, "password", password), true), 200).path("accessToken").asText();
        }
    }
    Map<String, Object> input(String email, List<String> roles, Map<String, Boolean> overrides) {
        return new HashMap<>(Map.of("email", email, "displayName", "Pilot User", "password", INITIAL,
            "active", true, "roleIds", roles, "overrides", overrides, "version", 0));
    }
    JsonNode create(String email, List<String> roles) throws Exception {
        return admin.read(admin.call("POST", "/users", input(email, roles, Map.of()), true), 201);
    }
    @Test void csrfAndWrongPasswordAreRejected() throws Exception {
        Client anonymous = new Client();
        anonymous.read(anonymous.call("POST", "/auth/login", Map.of("email", "admin@test.easyback.local", "password", "incorrect123!"), false), 403);
        anonymous.csrf();
        anonymous.read(anonymous.call("POST", "/auth/login", Map.of("email", "admin@test.easyback.local", "password", "incorrect123!"), true), 401);
    }
    @Test void retiredBootstrapSecretsLeaveEstablishedAccountsUnchanged() {
        var before=db.sql("SELECT row_to_json(u)::text FROM users u ORDER BY id").query(String.class).list();
        new Bootstrap(identityStore,passwordEncoder,"","").run(null);
        assertEquals(before,db.sql("SELECT row_to_json(u)::text FROM users u ORDER BY id").query(String.class).list());
    }
    @Test void emptyDatabaseFailsClosedWithoutBootstrapAndValidFirstBootSucceeds() {
        db.sql("TRUNCATE roles,users CASCADE").update();
        assertThrows(ApiException.class,()->new Bootstrap(identityStore,passwordEncoder,"","").run(null));
        assertEquals(0L,db.sql("SELECT count(*) FROM users").query(Long.class).single());
        assertThrows(IllegalStateException.class,()->new Bootstrap(identityStore,passwordEncoder,"",INITIAL).run(null));
        new Bootstrap(identityStore,passwordEncoder,"firstboot@test.local",INITIAL).run(null);
        assertEquals(1L,db.sql("SELECT count(*) FROM users").query(Long.class).single());
        assertTrue(passwordEncoder.matches(INITIAL,db.sql("SELECT password_hash FROM users").query(String.class).single()));
        new Bootstrap(identityStore,passwordEncoder,"","").run(null);
        assertEquals(1L,db.sql("SELECT count(*) FROM users").query(Long.class).single());
    }
    @Test void passwordChangeIsEnforcedAndRevokesSession() throws Exception {
        create("worker@test.local", List.of());
        Client worker = new Client(); worker.login("worker@test.local", INITIAL);
        worker.read(worker.call("GET", "/users", null, false), 403);
        worker.read(worker.call("POST", "/auth/password", Map.of("currentPassword", INITIAL, "newPassword", CHANGED), true), 204);
        worker.read(worker.call("GET", "/auth/me", null, false), 401);
        worker.access = null; worker.login("worker@test.local", CHANGED);
        assertFalse(worker.read(worker.call("GET", "/auth/me", null, false), 200).path("mustChangePassword").asBoolean());
        worker.read(worker.call("GET", "/users", null, false), 403);
        worker.read(worker.call("POST", "/users", input("blocked@test.local", List.of(), Map.of()), true), 403);
    }
    @Test void roleChangesAndDenialsApplyToExistingAccessTokens() throws Exception {
        var role = admin.read(admin.call("POST", "/roles", Map.of("name", "Pilot reader", "description", "", "version", 0, "permissions", List.of("users.view")), true), 201);
        String roleId = role.path("id").asText();
        var user = create("reader@test.local", List.of(roleId));
        db.sql("UPDATE users SET must_change_password = false WHERE email = 'reader@test.local'").update();
        Client reader = new Client(); reader.login("reader@test.local", INITIAL);
        reader.read(reader.call("GET", "/users", null, false), 200);
        var edit = input("reader@test.local", List.of(roleId), Map.of("users.view", false));
        admin.read(admin.call("PUT", "/users/" + user.path("id").asText(), edit, true), 200);
        reader.read(reader.call("GET", "/users", null, false), 403);
        edit.put("version", 1); edit.put("overrides", Map.of());
        admin.read(admin.call("PUT", "/users/" + user.path("id").asText(), edit, true), 200);
        reader.read(reader.call("GET", "/users", null, false), 200);
        admin.read(admin.call("PUT", "/roles/" + roleId, Map.of("name", "Pilot reader", "description", "", "version", 0, "permissions", List.of()), true), 200);
        reader.read(reader.call("GET", "/users", null, false), 403);
    }
    @Test void staleUpdatesDuplicateEmailsAndLastAdminAreProtected() throws Exception {
        var user = create("unique@test.local", List.of());
        admin.read(admin.call("POST", "/users", input("UNIQUE@test.local", List.of(), Map.of()), true), 409);
        var edit = input("unique@test.local", List.of(), Map.of()); edit.put("displayName", "Changed name");
        admin.read(admin.call("PUT", "/users/" + user.path("id").asText(), edit, true), 200);
        admin.read(admin.call("PUT", "/users/" + user.path("id").asText(), edit, true), 409);
        var me = admin.read(admin.call("GET", "/auth/me", null, false), 200);
        var self = input(me.path("email").asText(), List.of(), Map.of()); self.put("active", false);
        admin.read(admin.call("PUT", "/users/" + me.path("id").asText(), self, true), 409);
        admin.read(admin.call("GET", "/auth/me", null, false), 200);
    }
    @Test void disablingAndPasswordResetInvalidateActiveSessions() throws Exception {
        var user = create("disabled@test.local", List.of());
        Client worker = new Client(); worker.login("disabled@test.local", INITIAL);
        var edit = input("disabled@test.local", List.of(), Map.of()); edit.put("active", false);
        admin.read(admin.call("PUT", "/users/" + user.path("id").asText(), edit, true), 200);
        worker.read(worker.call("GET", "/auth/me", null, false), 401);
        var other = create("reset@test.local", List.of());
        Client otherClient = new Client(); otherClient.login("reset@test.local", INITIAL);
        admin.read(admin.call("POST", "/users/" + other.path("id").asText() + "/password", Map.of("password", CHANGED, "version", 0), true), 204);
        otherClient.read(otherClient.call("GET", "/auth/me", null, false), 401);
    }
    @Test void refreshRotationDetectsReplayAndLogoutRevokesAccess() throws Exception {
        String old = admin.cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("EB_REFRESH")).findFirst().orElseThrow().getValue();
        var refreshed = admin.read(admin.call("POST", "/auth/refresh", null, true), 200);
        admin.access = refreshed.path("accessToken").asText();
        admin.cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("EB_REFRESH")).forEach(c -> c.setValue(old));
        admin.read(admin.call("POST", "/auth/refresh", null, true), 401);
        admin.read(admin.call("GET", "/auth/me", null, false), 401);
        admin.access = null; admin.login("admin@test.easyback.local", "IntegrationAdmin123!");
        admin.read(admin.call("POST", "/auth/logout", null, true), 204);
        admin.read(admin.call("GET", "/auth/me", null, false), 401);
    }
    @Test void creatingUsersDoesNotPermitAssigningPrivileges() throws Exception {
        var creatorInput = input("creator@test.local", List.of(), Map.of("users.create", true));
        admin.read(admin.call("POST", "/users", creatorInput, true), 201);
        db.sql("UPDATE users SET must_change_password = false WHERE email = 'creator@test.local'").update();
        Client creator = new Client(); creator.login("creator@test.local", INITIAL);
        creator.read(creator.call("POST", "/users", input("plain@test.local", List.of(), Map.of()), true), 201);
        var me = admin.read(admin.call("GET", "/auth/me", null, false), 200);
        String adminRole = me.path("roleIds").get(0).asText();
        creator.read(creator.call("POST", "/users", input("escalated@test.local", List.of(adminRole), Map.of()), true), 403);
        creator.read(creator.call("POST", "/users", input("escalated@test.local", List.of(), Map.of("permissions.assign", true)), true), 403);
        creator.access += "tampered";
        creator.read(creator.call("GET", "/auth/me", null, false), 401);
    }
    @Test void concurrentAdministrationCannotDisableBothAdministrators() throws Exception {
        var me = admin.read(admin.call("GET", "/auth/me", null, false), 200);
        String role = me.path("roleIds").get(0).asText();
        var second = create("second-admin@test.local", List.of(role));
        db.sql("UPDATE users SET must_change_password = false WHERE email = 'second-admin@test.local'").update();
        Client other = new Client(); other.login("second-admin@test.local", INITIAL);
        var firstInput = input(me.path("email").asText(), List.of(role), Map.of()); firstInput.put("active", false);
        var secondInput = input("second-admin@test.local", List.of(role), Map.of()); secondInput.put("active", false);
        var first = CompletableFuture.supplyAsync(() -> {
            try { return admin.call("PUT", "/users/" + me.path("id").asText(), firstInput, true).statusCode(); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        var next = CompletableFuture.supplyAsync(() -> {
            try { return other.call("PUT", "/users/" + second.path("id").asText(), secondInput, true).statusCode(); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        assertEquals(Set.of(200, 409), Set.of(first.join(), next.join()));
        assertEquals(1L, db.sql("SELECT count(*) FROM users WHERE active = true").query(Long.class).single());
    }

    Map<String, Object> modelInput(String code) {
        return new HashMap<>(Map.of("code", code, "name", "Pilot model", "description", "", "active", true, "version", 0));
    }
    JsonNode model(String code) throws Exception { return admin.read(admin.call("POST", "/machine-models", modelInput(code), true), 201); }
    Map<String, Object> partInput(String code) {
        var input = new HashMap<String, Object>();
        input.putAll(Map.of("code", code, "name", "Pilot plate", "description", "", "kind", "SERIALIZED_PIECE", "shape", "RECTANGULAR",
            "stockUnit", "PIECE", "lengthMm", 120.125, "widthMm", 60, "defaultInstallMinutes", 12.5));
        input.put("diameterMm", null); input.put("active", true); input.put("version", 0); return input;
    }
    JsonNode part(String code) throws Exception { return admin.read(admin.call("POST", "/parts", partInput(code), true), 201); }
    JsonNode draft(String model) throws Exception { return admin.read(admin.call("POST", "/machine-models/" + model + "/boms", Map.of(), true), 201); }
    Map<String, Object> itemInput(String position, String part, long version) {
        return new HashMap<>(Map.of("positionCode", position, "partId", part, "quantity", 2, "expectedInstallMinutes", 12.5, "notes", "", "version", version));
    }
    @Test void catalogNormalizesCodesValidatesDimensionsAndDetectsStaleEdits() throws Exception {
        var m = model("model-01"); assertEquals("MODEL-01", m.path("code").asText());
        admin.read(admin.call("POST", "/machine-models", modelInput("MODEL-01"), true), 409);
        var edit = modelInput("model-01"); edit.put("name", "Changed model");
        admin.read(admin.call("PUT", "/machine-models/" + m.path("id").asText(), edit, true), 200);
        admin.read(admin.call("PUT", "/machine-models/" + m.path("id").asText(), edit, true), 409);
        var invalid = partInput("bad-shape"); invalid.put("diameterMm", 30);
        assertEquals("INVALID_SPECIFICATION", admin.read(admin.call("POST", "/parts", invalid, true), 400).path("code").asText());
        invalid = partInput("bad-precision"); invalid.put("lengthMm", 1.1234);
        admin.read(admin.call("POST", "/parts", invalid, true), 400);
        invalid = partInput("negative"); invalid.put("widthMm", -1); admin.read(admin.call("POST", "/parts", invalid, true), 400);
        var raw = partInput("raw"); raw.put("kind", "MEASURED_STOCK"); raw.put("stockUnit", "AREA"); raw.put("defaultInstallMinutes", 0);
        admin.read(admin.call("POST", "/parts", raw, true), 201);
        var round = partInput("shaft"); round.put("shape", "ROUND"); round.put("widthMm", null); round.put("diameterMm", 25.5);
        admin.read(admin.call("POST", "/parts", round, true), 201);
        assertEquals(4L, db.sql("SELECT count(*) FROM audit_logs WHERE action IN ('MODEL_CREATED','MODEL_UPDATED','PART_CREATED')").query(Long.class).single());
    }
    @Test void machineIdentityAndInactiveModelAreProtected() throws Exception {
        var m = model("model"); String mid = m.path("id").asText();
        var data = new HashMap<String, Object>(Map.of("serialNumber", "machine-01", "modelId", mid, "name", "Pilot machine", "location", "Hall A", "notes", "", "active", true, "version", 0));
        var machine = admin.read(admin.call("POST", "/machines", data, true), 201);
        assertEquals("MACHINE-01", machine.path("serialNumber").asText());
        admin.read(admin.call("POST", "/machines", data, true), 409);
        data.put("serialNumber", "machine-02");
        assertEquals("MACHINE_IDENTITY_IMMUTABLE", admin.read(admin.call("PUT", "/machines/" + machine.path("id").asText(), data, true), 409).path("code").asText());
        var archived = modelInput("model"); archived.put("active", false);
        admin.read(admin.call("PUT", "/machine-models/" + mid, archived, true), 200);
        admin.read(admin.call("POST", "/machines", data, true), 409);
        admin.read(admin.call("POST", "/machine-models/" + mid + "/boms", Map.of(), true), 409);
    }
    @Test void repeatedPartsPublishAsImmutableSnapshotsAndCloneIntoNewRevisions() throws Exception {
        String mid = model("m1").path("id").asText(), pid = part("plate").path("id").asText();
        String bid = draft(mid).path("id").asText();
        admin.read(admin.call("POST", "/machine-models/" + mid + "/boms", Map.of(), true), 409);
        admin.read(admin.call("POST", "/boms/" + bid + "/publish", Map.of("version", 0), true), 409);
        var first = admin.read(admin.call("POST", "/boms/" + bid + "/items", itemInput("A-01", pid, 0), true), 201);
        admin.read(admin.call("POST", "/boms/" + bid + "/items", itemInput("a-01", pid, 1), true), 409);
        var second = admin.read(admin.call("POST", "/boms/" + bid + "/items", itemInput("A-02", pid, 1), true), 201);
        assertEquals(2, second.path("items").size());
        var spec = partInput("plate"); spec.put("lengthMm", 200);
        assertEquals("SPECIFICATION_IN_USE", admin.read(admin.call("PUT", "/parts/" + pid, spec, true), 409).path("code").asText());
        spec.put("lengthMm", 120.125); spec.put("name", "Renamed plate"); spec.put("defaultInstallMinutes", 25);
        admin.read(admin.call("PUT", "/parts/" + pid, spec, true), 200);
        var published = admin.read(admin.call("POST", "/boms/" + bid + "/publish", Map.of("version", 2), true), 200);
        assertEquals("PUBLISHED", published.path("status").asText()); assertFalse(published.path("publishedAt").isNull());
        assertEquals("Pilot plate", published.path("items").get(0).path("partName").asText());
        assertEquals(12.5, published.path("items").get(0).path("expectedInstallMinutes").asDouble());
        admin.read(admin.call("POST", "/boms/" + bid + "/items", itemInput("A-03", pid, 3), true), 409);
        String iid = first.path("items").get(0).path("id").asText();
        admin.read(admin.call("PUT", "/boms/" + bid + "/items/" + iid, itemInput("A-01", pid, 3), true), 409);
        admin.read(admin.call("DELETE", "/boms/" + bid + "/items/" + iid, Map.of("version", 3), true), 409);
        var clone = draft(mid); assertEquals(2, clone.path("revision").asInt()); assertEquals(2, clone.path("items").size());
        assertNotEquals(iid, clone.path("items").get(0).path("id").asText());
        String other = draft(model("m2").path("id").asText()).path("id").asText();
        admin.read(admin.call("POST", "/boms/" + other + "/items", itemInput("B-01", pid, 0), true), 201);
    }
    @Test void rawMaterialsInactivePartsAndCrossBomItemsAreRejected() throws Exception {
        String mid = model("m").path("id").asText(), bid = draft(mid).path("id").asText();
        var raw = partInput("raw"); raw.put("kind", "MEASURED_STOCK"); raw.put("stockUnit", "AREA"); raw.put("defaultInstallMinutes", 0);
        String rid = admin.read(admin.call("POST", "/parts", raw, true), 201).path("id").asText();
        assertEquals("RAW_MATERIAL_NOT_INSTALLABLE", admin.read(admin.call("POST", "/boms/" + bid + "/items", itemInput("P1", rid, 0), true), 409).path("code").asText());
        String pid = part("p").path("id").asText();
        admin.read(admin.call("PUT", "/boms/" + bid + "/items/" + UUID.randomUUID(), itemInput("P1", pid, 0), true), 409);
        var changed = admin.read(admin.call("POST", "/boms/" + bid + "/items", itemInput("P1", pid, 0), true), 201);
        String iid = changed.path("items").get(0).path("id").asText();
        var p = partInput("p"); p.put("active", false); admin.read(admin.call("PUT", "/parts/" + pid, p, true), 200);
        admin.read(admin.call("POST", "/boms/" + bid + "/publish", Map.of("version", 1), true), 409);
        admin.read(admin.call("POST", "/boms/" + bid + "/items", itemInput("P2", pid, 1), true), 409);
        admin.read(admin.call("DELETE", "/boms/" + bid + "/items/" + iid, Map.of("version", 1), true), 200);
        assertEquals(0, admin.read(admin.call("GET", "/boms/" + bid, null, false), 200).path("items").size());
    }
    @Test void catalogAuthorizationAppliesToExistingSessionsAndRequiresReadPrerequisites() throws Exception {
        var input = input("viewer@test.local", List.of(), Map.of("catalog.view", true, "boms.view", true, "machines.view", true));
        var user = admin.read(admin.call("POST", "/users", input, true), 201);
        db.sql("UPDATE users SET must_change_password=false WHERE email='viewer@test.local'").update();
        Client viewer = new Client(); viewer.login("viewer@test.local", INITIAL);
        String mid = model("m").path("id").asText();
        for (var path : List.of("/machine-models", "/parts", "/machines", "/machine-models/" + mid + "/boms")) viewer.read(viewer.call("GET", path, null, false), 200);
        viewer.read(viewer.call("POST", "/machine-models", modelInput("denied"), true), 403);
        viewer.read(viewer.call("POST", "/machine-models/" + mid + "/boms", Map.of(), true), 403);
        input.put("overrides", Map.of("catalog.manage", true, "boms.manage", true));
        admin.read(admin.call("PUT", "/users/" + user.path("id").asText(), input, true), 200);
        viewer.read(viewer.call("POST", "/machine-models", modelInput("still-denied"), true), 403);
        viewer.read(viewer.call("GET", "/parts", null, false), 403);
    }
    @Test void concurrentBomChangesHaveOneWinnerAndNoDuplicateDraft() throws Exception {
        String mid = model("m").path("id").asText(), pid = part("p").path("id").asText();
        var drafts = List.of(CompletableFuture.supplyAsync(() -> catalogStatus("POST", "/machine-models/" + mid + "/boms", Map.of())),
            CompletableFuture.supplyAsync(() -> catalogStatus("POST", "/machine-models/" + mid + "/boms", Map.of())));
        assertEquals(Set.of(201, 409), Set.of(drafts.get(0).join(), drafts.get(1).join()));
        String bid = admin.read(admin.call("GET", "/machine-models/" + mid + "/boms", null, false), 200).get(0).path("id").asText();
        var a = CompletableFuture.supplyAsync(() -> catalogStatus("POST", "/boms/" + bid + "/items", itemInput("P1", pid, 0)));
        var b = CompletableFuture.supplyAsync(() -> catalogStatus("POST", "/boms/" + bid + "/items", itemInput("P2", pid, 0)));
        assertEquals(Set.of(201, 409), Set.of(a.join(), b.join()));
        assertEquals(1, admin.read(admin.call("GET", "/boms/" + bid, null, false), 200).path("items").size());
    }
    JsonNode machine(String serial, String modelId) throws Exception {
        return admin.read(admin.call("POST", "/machines", Map.of("serialNumber", serial, "modelId", modelId,
            "name", serial, "location", "", "notes", "", "active", true, "version", 0), true), 201);
    }
    @Test void linkedPartCreationIsAtomicAndNeverEditsPublishedVersions() throws Exception {
        String mid = model("linked-model").path("id").asText();
        admin.read(admin.call("POST", "/parts/with-model", Map.of("part", partInput("linked-part"), "modelId", mid, "positionCode", "A1", "quantity", 3), true), 201);
        var draft = admin.read(admin.call("GET", "/machine-models/" + mid + "/boms", null, false), 200).get(0);
        assertEquals(3, draft.path("items").get(0).path("quantity").asInt());
        long auditCount = db.sql("SELECT count(*) FROM audit_logs").query(Long.class).single();
        admin.read(admin.call("POST", "/parts/with-model", Map.of("part", partInput("rolled-back"), "modelId", mid, "positionCode", "a1", "quantity", 1), true), 409);
        assertEquals(0L, db.sql("SELECT count(*) FROM parts WHERE code='ROLLED-BACK'").query(Long.class).single());
        assertEquals(auditCount, db.sql("SELECT count(*) FROM audit_logs").query(Long.class).single());
        String bid = draft.path("id").asText();
        admin.read(admin.call("POST", "/boms/" + bid + "/publish", Map.of("version", 1), true), 200);
        admin.read(admin.call("POST", "/parts/with-model", Map.of("part", partInput("next-part"), "modelId", mid, "positionCode", "A2", "quantity", 1), true), 201);
        var revisions = admin.read(admin.call("GET", "/machine-models/" + mid + "/boms", null, false), 200);
        assertEquals(2, revisions.size()); assertEquals(2, revisions.get(0).path("items").size());
        assertEquals(1, revisions.get(1).path("items").size());
        var raw = partInput("raw-linked"); raw.put("kind", "MEASURED_STOCK"); raw.put("stockUnit", "AREA"); raw.put("defaultInstallMinutes", 0);
        admin.read(admin.call("POST", "/parts/with-model", Map.of("part", raw, "modelId", mid, "positionCode", "A3", "quantity", 1), true), 409);
        admin.read(admin.call("POST", "/parts/with-model", Map.of("part", partInput("invalid"), "modelId", mid, "positionCode", "!", "quantity", 0), true), 400);
        assertEquals(2L, db.sql("SELECT count(*) FROM parts").query(Long.class).single());
    }
    @Test void machineListsAreIndependentSnapshotsWithProtectedSourcesAndRevisions() throws Exception {
        String mid = model("shared").path("id").asText(), pid = part("shared-part").path("id").asText();
        String template = draft(mid).path("id").asText();
        admin.read(admin.call("POST", "/boms/" + template + "/items", itemInput("A1", pid, 0), true), 201);
        admin.read(admin.call("POST", "/boms/" + template + "/publish", Map.of("version", 1), true), 200);
        String first = machine("machine-1", mid).path("id").asText(), second = machine("machine-2", mid).path("id").asText();
        String path = "/machines/" + first + "/boms";
        var clone = admin.read(admin.call("POST", path, Map.of("sourceBomId", template), true), 201);
        String bid = clone.path("id").asText(); assertEquals(first, clone.path("machineId").asText());
        assertEquals(1, clone.path("items").size());
        var empty = admin.read(admin.call("POST", "/machines/" + second + "/boms", Map.of(), true), 201);
        assertEquals(0, empty.path("items").size());
        admin.read(admin.call("POST", path, Map.of(), true), 409);
        var changed = admin.read(admin.call("POST", "/boms/" + bid + "/items", itemInput("A2", pid, 0), true), 201);
        admin.read(admin.call("POST", "/boms/" + bid + "/items", itemInput("STALE", pid, 0), true), 409);
        String iid = changed.path("items").get(0).path("id").asText();
        admin.read(admin.call("PUT", "/boms/" + bid + "/items/" + iid, itemInput("A1", pid, 1), true), 200);
        admin.read(admin.call("DELETE", "/boms/" + bid + "/items/" + iid, Map.of("version", 2), true), 200);
        admin.read(admin.call("POST", "/boms/" + bid + "/publish", Map.of("version", 3), true), 200);
        admin.read(admin.call("POST", "/boms/" + bid + "/items", itemInput("LOCKED", pid, 4), true), 409);
        assertEquals(1, admin.read(admin.call("GET", "/machine-models/" + mid + "/boms", null, false), 200).size());
        assertEquals("A1", admin.read(admin.call("GET", "/boms/" + template, null, false), 200).path("items").get(0).path("positionCode").asText());
        assertEquals(0, admin.read(admin.call("GET", "/boms/" + empty.path("id").asText(), null, false), 200).path("items").size());
        String third = machine("machine-3", mid).path("id").asText();
        admin.read(admin.call("POST", "/machines/" + third + "/boms", Map.of("sourceBomId", bid), true), 409);
        admin.read(admin.call("POST", "/machine-models/" + mid + "/boms", Map.of("sourceBomId", bid), true), 409);
        String otherModel = model("other").path("id").asText(), otherMachine = machine("other-machine", otherModel).path("id").asText();
        admin.read(admin.call("POST", "/machines/" + otherMachine + "/boms", Map.of("sourceBomId", template), true), 409);
        var next = admin.read(admin.call("POST", path, Map.of(), true), 201);
        assertEquals(2, next.path("revision").asInt()); assertEquals("A2", next.path("items").get(0).path("positionCode").asText());
        db.sql("UPDATE machines SET active=false WHERE id=:id").param("id", UUID.fromString(first)).update();
        admin.read(admin.call("POST", "/boms/" + next.path("id").asText() + "/items", itemInput("INACTIVE", pid, 0), true), 409);
    }
    @Test void machineListsRequireMachinePermissionsEvenThroughDirectBomUrls() throws Exception {
        String mid = model("permission-model").path("id").asText(), pid = part("p").path("id").asText();
        String machine = machine("permission-machine", mid).path("id").asText();
        String path = "/machines/" + machine + "/boms";
        String bid = admin.read(admin.call("POST", path, Map.of(), true), 201).path("id").asText();
        var item = admin.read(admin.call("POST", "/boms/" + bid + "/items", itemInput("A1", pid, 0), true), 201).path("items").get(0).path("id").asText();
        var permissions = new HashMap<>(Map.of("catalog.view", true, "catalog.manage", true, "boms.view", true, "boms.manage", true));
        var userInput = input("model-editor@test.local", List.of(), permissions);
        String uid = admin.read(admin.call("POST", "/users", userInput, true), 201).path("id").asText();
        db.sql("UPDATE users SET must_change_password=false WHERE email='model-editor@test.local'").update();
        Client editor = new Client(); editor.login("model-editor@test.local", INITIAL);
        editor.read(editor.call("GET", path, null, false), 403);
        editor.read(editor.call("GET", "/boms/" + bid, null, false), 403);
        permissions.put("machines.view", true);
        admin.read(admin.call("PUT", "/users/" + uid, userInput, true), 200);
        editor.read(editor.call("GET", "/boms/" + bid, null, false), 200);
        editor.read(editor.call("POST", path, Map.of(), true), 403);
        editor.read(editor.call("POST", "/boms/" + bid + "/items", itemInput("A2", pid, 1), true), 403);
        editor.read(editor.call("PUT", "/boms/" + bid + "/items/" + item, itemInput("A1", pid, 1), true), 403);
        editor.read(editor.call("DELETE", "/boms/" + bid + "/items/" + item, Map.of("version", 1), true), 403);
        editor.read(editor.call("POST", "/boms/" + bid + "/publish", Map.of("version", 1), true), 403);
        permissions.put("boms.manage", false); userInput.put("version", 1);
        admin.read(admin.call("PUT", "/users/" + uid, userInput, true), 200);
        editor.read(editor.call("POST", "/parts/with-model", Map.of("part", partInput("denied"), "modelId", mid, "positionCode", "A1", "quantity", 1), true), 403);
        assertEquals(1L, db.sql("SELECT count(*) FROM parts").query(Long.class).single());
    }
    record OrderFixture(String model, String machine, String bom, String part, String owner) {}
    OrderFixture orderFixture() throws Exception {
        String mid=model("order-model").path("id").asText(), pid=part("order-part").path("id").asText();
        String machine=machine("order-machine",mid).path("id").asText();
        String bom=admin.read(admin.call("POST","/machines/"+machine+"/boms",Map.of(),true),201).path("id").asText();
        admin.read(admin.call("POST","/boms/"+bom+"/items",itemInput("A1",pid,0),true),201);
        admin.read(admin.call("POST","/boms/"+bom+"/items",itemInput("A2",pid,1),true),201);
        admin.read(admin.call("POST","/boms/"+bom+"/publish",Map.of("version",2),true),200);
        return new OrderFixture(mid,machine,bom,pid,admin.read(admin.call("GET","/auth/me",null,false),200).path("id").asText());
    }
    Map<String,Object> orderInput(String code, OrderFixture f) {
        return new HashMap<>(Map.of("code",code,"title","Pilot manufacturing order","machineId",f.machine(),"bomId",f.bom(),
            "responsibleId",f.owner(),"deliveryAt",Instant.now().plusSeconds(864000).toString(),"notes","Order notes","version",0));
    }
    JsonNode newOrder(Map<String,Object> p) throws Exception { return admin.read(admin.call("POST","/manufacturing-orders",p,true),201); }
    @Test void orderDraftEditReleaseAndCancellationPreserveFrozenRequirements() throws Exception {
        var f=orderFixture(); var p=orderInput("mo-1",f); var created=newOrder(p); String path="/manufacturing-orders/"+created.path("id").asText();
        assertEquals("MO-1",created.path("code").asText()); assertEquals(2,created.path("items").size());
        assertEquals("DRAFT",created.path("status").asText());
        admin.read(admin.call("POST","/manufacturing-orders",p,true),409);
        p.put("title","Updated title");
        var edited=admin.read(admin.call("PUT",path,p,true),200); assertEquals(created.path("items"),edited.path("items"));
        admin.read(admin.call("PUT",path,p,true),409);
        admin.read(admin.call("POST",path+"/release",Map.of("version",0),true),409);
        var opened=admin.read(admin.call("POST",path+"/release",Map.of("version",1),true),200);
        assertEquals("OPEN",opened.path("status").asText()); assertFalse(opened.path("openedAt").isNull());
        p.put("version",2); admin.read(admin.call("PUT",path,p,true),409);
        admin.read(admin.call("POST",path+"/release",Map.of("version",2),true),409);
        var part=partInput("order-part"); part.put("name","Changed catalog name"); part.put("defaultInstallMinutes",50);
        admin.read(admin.call("PUT","/parts/"+f.part(),part,true),200);
        String next=admin.read(admin.call("POST","/machines/"+f.machine()+"/boms",Map.of(),true),201).path("id").asText();
        admin.read(admin.call("POST","/boms/"+next+"/items",itemInput("A3",f.part(),0),true),201);
        admin.read(admin.call("POST","/boms/"+next+"/publish",Map.of("version",1),true),200);
        assertEquals(created.path("items"),admin.read(admin.call("GET",path,null,false),200).path("items"));
        admin.read(admin.call("POST",path+"/cancel",Map.of("version",2,"reason","   "),true),400);
        var cancelled=admin.read(admin.call("POST",path+"/cancel",Map.of("version",2,"reason","Pilot cancellation"),true),200);
        assertEquals("CANCELLED",cancelled.path("status").asText()); assertEquals(created.path("items"),cancelled.path("items"));
        admin.read(admin.call("POST",path+"/cancel",Map.of("version",3,"reason","Again"),true),409);
        assertEquals(4L,db.sql("SELECT count(*) FROM audit_logs WHERE action LIKE 'ORDER_%'").query(Long.class).single());
    }
    @Test void ordersRejectUnpublishedOrForeignBomsAndRefreshRequirementsOnlyInDrafts() throws Exception {
        var f=orderFixture(); var p=orderInput("o1",f);
        p.put("bomId",draft(f.model()).path("id").asText());
        admin.read(admin.call("POST","/manufacturing-orders",p,true),409);
        String other=machine("other-machine",f.model()).path("id").asText(); p.put("bomId",f.bom()); p.put("machineId",other);
        admin.read(admin.call("POST","/manufacturing-orders",p,true),409);
        String draft=admin.read(admin.call("POST","/machines/"+f.machine()+"/boms",Map.of(),true),201).path("id").asText();
        p.put("machineId",f.machine()); p.put("bomId",draft);
        admin.read(admin.call("POST","/manufacturing-orders",p,true),409);
        assertEquals(0L,db.sql("SELECT count(*) FROM manufacturing_orders").query(Long.class).single());
        p.put("bomId",f.bom()); String path="/manufacturing-orders/"+newOrder(p).path("id").asText();
        admin.read(admin.call("POST","/boms/"+draft+"/items",itemInput("A3",f.part(),0),true),201);
        admin.read(admin.call("POST","/boms/"+draft+"/publish",Map.of("version",1),true),200);
        p.put("bomId",draft); var edited=admin.read(admin.call("PUT",path,p,true),200);
        assertEquals(3,edited.path("items").size()); assertEquals(2,edited.path("bomRevision").asInt());
    }
    @Test void ordersValidateDeliveryAndRecheckActiveReferencesAtRelease() throws Exception {
        var f=orderFixture(); var p=orderInput("validations",f);
        p.put("deliveryAt","2020-01-01T00:00:00Z"); admin.read(admin.call("POST","/manufacturing-orders",p,true),409);
        p.put("deliveryAt","not-a-date"); admin.read(admin.call("POST","/manufacturing-orders",p,true),400);
        p=orderInput("validations",f); p.remove("deliveryAt"); admin.read(admin.call("POST","/manufacturing-orders",p,true),400);
        p=orderInput("validations",f); p.put("responsibleId",create("no-order-access@test.local",List.of()).path("id").asText());
        admin.read(admin.call("POST","/manufacturing-orders",p,true),409);
        var order=newOrder(orderInput("validations",f)); String path="/manufacturing-orders/"+order.path("id").asText();
        db.sql("UPDATE parts SET active=false WHERE id=:id").param("id",UUID.fromString(f.part())).update();
        admin.read(admin.call("POST",path+"/release",Map.of("version",0),true),409);
        db.sql("UPDATE parts SET active=true WHERE id=:id").param("id",UUID.fromString(f.part())).update();
        db.sql("UPDATE machines SET active=false WHERE id=:id").param("id",UUID.fromString(f.machine())).update();
        admin.read(admin.call("POST",path+"/release",Map.of("version",0),true),409);
        admin.read(admin.call("POST",path+"/cancel",Map.of("version",0,"reason","Archived machine"),true),200);
        assertEquals(0L,db.sql("SELECT count(*) FROM audit_logs WHERE action='ORDER_OPENED'").query(Long.class).single());
    }
    @Test void assignedOnlyOrderVisibilityIsEnforcedOnListsDetailsAndLiveRevocation() throws Exception {
        var f=orderFixture(); var userInput=input("assigned@test.local",List.of(),Map.of("orders.view-assigned",true));
        String uid=admin.read(admin.call("POST","/users",userInput,true),201).path("id").asText();
        db.sql("UPDATE users SET must_change_password=false WHERE email='assigned@test.local'").update();
        var p=orderInput("mine",f); p.put("responsibleId",uid); var mine=newOrder(p);
        var other=newOrder(orderInput("other",f));
        Client worker=new Client(); worker.login("assigned@test.local",INITIAL);
        var list=worker.read(worker.call("GET","/manufacturing-orders",null,false),200);
        assertEquals(1,list.size()); assertEquals(mine.path("id"),list.get(0).path("id"));
        String path="/manufacturing-orders/"+mine.path("id").asText();
        worker.read(worker.call("GET",path,null,false),200);
        worker.read(worker.call("GET","/manufacturing-orders/"+other.path("id").asText(),null,false),404);
        worker.read(worker.call("GET","/manufacturing-orders/responsible-people",null,false),403);
        worker.read(worker.call("POST","/manufacturing-orders",p,true),403);
        worker.read(worker.call("PUT",path,p,true),403);
        worker.read(worker.call("POST",path+"/release",Map.of("version",0),true),403);
        worker.read(worker.call("POST",path+"/cancel",Map.of("version",0,"reason","Not permitted"),true),403);
        userInput.put("overrides",Map.of("orders.view-assigned",false));
        admin.read(admin.call("PUT","/users/"+uid,userInput,true),200);
        worker.read(worker.call("GET",path,null,false),403);
        admin.read(admin.call("POST",path+"/release",Map.of("version",0),true),409);
        assertTrue(admin.read(admin.call("GET","/manufacturing-orders/responsible-people",null,false),200).toString().indexOf(uid)<0);
    }
    @Test void orderTransitionPermissionsAreSeparateFromDraftManagement() throws Exception {
        var f=orderFixture(); var perms=new HashMap<>(Map.of("orders.view",true,"orders.manage",true,"catalog.view",true,"machines.view",true,"boms.view",true));
        var ui=input("order-editor@test.local",List.of(),perms);
        String uid=admin.read(admin.call("POST","/users",ui,true),201).path("id").asText();
        db.sql("UPDATE users SET must_change_password=false WHERE email='order-editor@test.local'").update();
        Client editor=new Client(); editor.login("order-editor@test.local",INITIAL);
        var p=orderInput("managed",f); String path="/manufacturing-orders/"+editor.read(editor.call("POST","/manufacturing-orders",p,true),201).path("id").asText();
        editor.read(editor.call("POST",path+"/release",Map.of("version",0),true),403);
        editor.read(editor.call("POST",path+"/cancel",Map.of("version",0,"reason","No permission"),true),403);
        perms.put("orders.manage",false); perms.put("orders.release",true);
        admin.read(admin.call("PUT","/users/"+uid,ui,true),200);
        editor.read(editor.call("PUT",path,p,true),403);
        editor.read(editor.call("POST",path+"/release",Map.of("version",0),true),200);
        perms.put("orders.view",false); ui.put("version",1);
        admin.read(admin.call("PUT","/users/"+uid,ui,true),200);
        editor.read(editor.call("GET",path,null,false),403);
    }
    @Test void concurrentOrderReleaseAndCancellationHaveOnlyOneWinner() throws Exception {
        var f=orderFixture(); String path="/manufacturing-orders/"+newOrder(orderInput("race",f)).path("id").asText();
        var a=CompletableFuture.supplyAsync(() -> catalogStatus("POST",path+"/release",Map.of("version",0)));
        var b=CompletableFuture.supplyAsync(() -> catalogStatus("POST",path+"/cancel",Map.of("version",0,"reason","Concurrent cancellation")));
        assertEquals(Set.of(200,409),Set.of(a.join(),b.join()));
        assertEquals(1,admin.read(admin.call("GET",path,null,false),200).path("version").asInt());
        assertEquals(1L,db.sql("SELECT count(*) FROM audit_logs WHERE action IN ('ORDER_OPENED','ORDER_CANCELLED')").query(Long.class).single());
    }
    Map<String,Object> receiptInput(String part,String...serials) {
        return Map.of("partId",part,"serials",List.of(serials),"location","Shelf A","reference","Pilot receipt","notes","");
    }
    JsonNode receipt(String part,String...serials) throws Exception { return admin.read(admin.call("POST","/warehouse/receipts",receiptInput(part,serials),true),201); }
    JsonNode openStockOrder(OrderFixture f,String code) throws Exception {
        var o=newOrder(orderInput(code,f));
        return admin.read(admin.call("POST","/manufacturing-orders/"+o.path("id").asText()+"/release",Map.of("version",0),true),200);
    }
    JsonNode stock(String order) throws Exception { return admin.read(admin.call("GET","/manufacturing-orders/"+order+"/stock",null,false),200); }
    @Test void stockReceiptsAreAtomicAndSerialsAndSpecificationsAreProtected() throws Exception {
        String pid=part("stock-only").path("id").asText();
        receipt(pid,"serial-1");
        assertEquals("SERIAL-1",admin.read(admin.call("GET","/warehouse/pieces",null,false),200).get(0).path("serialNumber").asText());
        admin.read(admin.call("POST","/warehouse/receipts",receiptInput(pid,"serial-2","SERIAL-1"),true),409);
        admin.read(admin.call("POST","/warehouse/receipts",receiptInput(pid,"x","X"),true),409);
        assertEquals(1L,db.sql("SELECT count(*) FROM stock_pieces").query(Long.class).single());
        assertEquals(1L,db.sql("SELECT count(*) FROM stock_movements").query(Long.class).single());
        var spec=partInput("stock-only"); spec.put("lengthMm",200);
        admin.read(admin.call("PUT","/parts/"+pid,spec,true),409);
        var raw=partInput("raw-stock"); raw.put("kind","MEASURED_STOCK"); raw.put("stockUnit","AREA"); raw.put("defaultInstallMinutes",0);
        String rawId=admin.read(admin.call("POST","/parts",raw,true),201).path("id").asText();
        admin.read(admin.call("POST","/warehouse/receipts",receiptInput(rawId,"raw-serial"),true),409);
        admin.read(admin.call("POST","/warehouse/receipts",receiptInput(pid,"invalid space"),true),400);
        var archive=partInput("stock-only"); archive.put("active",false);
        admin.read(admin.call("PUT","/parts/"+pid,archive,true),200);
        admin.read(admin.call("POST","/warehouse/receipts",receiptInput(pid,"inactive"),true),409);
    }
    @Test void openingReservesOnlyAvailableStockAndRepeatedPositionsCannotOverAllocate() throws Exception {
        var f=orderFixture(); receipt(f.part(),"S1","S2","S3");
        String first=openStockOrder(f,"first").path("id").asText();
        var s=stock(first); assertEquals(3,s.path("pieces").size());
        assertEquals(2,s.path("requirements").get(0).path("reserved").asInt());
        assertEquals(1,s.path("requirements").get(1).path("missing").asInt());
        String second=openStockOrder(f,"second").path("id").asText(); assertEquals(0,stock(second).path("pieces").size());
        receipt(f.part(),"S4","S5");
        admin.read(admin.call("POST","/manufacturing-orders/"+first+"/stock/reserve",Map.of("version",1),true),200);
        assertEquals(4,stock(first).path("pieces").size());
        admin.read(admin.call("POST","/manufacturing-orders/"+first+"/stock/reserve",Map.of("version",2),true),200);
        assertEquals(4,stock(first).path("pieces").size());
        admin.read(admin.call("POST","/manufacturing-orders/"+second+"/stock/reserve",Map.of("version",1),true),200);
        assertEquals(1,stock(second).path("pieces").size());
        var b=admin.read(admin.call("GET","/warehouse/balances",null,false),200).get(0);
        assertEquals(0,b.path("available").asInt()); assertEquals(5,b.path("reserved").asInt());
    }
    @Test void issuingIsAtomicRejectsForeignSelectionsAndKeepsStockInsideUntilIssue() throws Exception {
        var f=orderFixture(); receipt(f.part(),"S1","S2");
        String first=openStockOrder(f,"first").path("id").asText(), second=openStockOrder(f,"second").path("id").asText();
        var s=stock(first); String piece=s.path("pieces").get(0).path("id").asText();
        String path="/manufacturing-orders/"+first+"/stock/issue";
        admin.read(admin.call("POST",path,Map.of("version",1,"pieceIds",List.of(piece,UUID.randomUUID().toString()),"notes",""),true),404);
        assertEquals("RESERVED",stock(first).path("pieces").get(0).path("status").asText());
        admin.read(admin.call("POST","/manufacturing-orders/"+second+"/stock/issue",Map.of("version",1,"pieceIds",List.of(piece),"notes",""),true),409);
        admin.read(admin.call("POST",path,Map.of("version",1,"pieceIds",List.of(piece,piece),"notes",""),true),409);
        var issued=admin.read(admin.call("POST",path,Map.of("version",1,"pieceIds",List.of(piece),"notes","Issued to responsible user"),true),200);
        assertEquals(2,issued.path("version").asInt());
        admin.read(admin.call("POST",path,Map.of("version",2,"pieceIds",List.of(piece),"notes",""),true),409);
        var b=admin.read(admin.call("GET","/warehouse/balances",null,false),200).get(0);
        assertEquals(1,b.path("reserved").asInt()); assertEquals(1,b.path("issued").asInt());
        assertEquals(1L,db.sql("SELECT count(*) FROM stock_movements WHERE action='ISSUED'").query(Long.class).single());
    }
    @Test void cancelReleasesReservationsButIssuedPiecesMustBeReturnedAndDefectsAreIsolated() throws Exception {
        var f=orderFixture(); receipt(f.part(),"S1","S2"); String oid=openStockOrder(f,"returns").path("id").asText();
        var s=stock(oid); String piece=s.path("pieces").get(0).path("id").asText(); String path="/manufacturing-orders/"+oid;
        admin.read(admin.call("POST",path+"/stock/issue",Map.of("version",1,"pieceIds",List.of(piece),"notes",""),true),200);
        admin.read(admin.call("POST",path+"/cancel",Map.of("version",2,"reason","Need return"),true),409);
        admin.read(admin.call("POST",path+"/stock/pieces/"+piece+"/return",Map.of("version",2,"condition","DEFECTIVE","notes","Defect found"),true),200);
        admin.read(admin.call("POST",path+"/cancel",Map.of("version",3,"reason","Returned"),true),200);
        assertEquals(0,stock(oid).path("pieces").size());
        var pieces=admin.read(admin.call("GET","/warehouse/pieces",null,false),200);
        JsonNode quarantined=null; for(var p:pieces) if(p.path("id").asText().equals(piece)) quarantined=p;
        assertEquals("QUARANTINED",quarantined.path("status").asText());
        admin.read(admin.call("POST","/warehouse/pieces/"+piece+"/restore",Map.of("version",quarantined.path("version").asLong(),"notes","Repaired and inspected"),true),200);
        assertEquals(2,admin.read(admin.call("GET","/warehouse/balances",null,false),200).get(0).path("available").asInt());
        assertEquals(1L,db.sql("SELECT count(*) FROM stock_movements WHERE action='UNRESERVED'").query(Long.class).single());
        admin.read(admin.call("POST",path+"/stock/reserve",Map.of("version",4),true),409);
    }
    @Test void shortagesFlowToPreparationAndFulfillFromActualReservedReceipts() throws Exception {
        var f=orderFixture(); receipt(f.part(),"S1"); String oid=openStockOrder(f,"preparation").path("id").asText();
        var c=stock(oid).path("requirements").get(0); String path="/manufacturing-orders/"+oid+"/stock";
        var request=Map.of("version",1,"requirementId",c.path("requirementId").asText(),"notes","Prepare missing piece");
        admin.read(admin.call("POST",path+"/preparation",request,true),200);
        var q=admin.read(admin.call("GET","/warehouse/preparations",null,false),200).get(0);
        assertEquals(1,q.path("missing").asInt());
        admin.read(admin.call("POST","/warehouse/preparations/"+q.path("id").asText()+"/start",Map.of("version",0),true),200);
        assertEquals("IN_PROGRESS",admin.read(admin.call("GET","/warehouse/preparations",null,false),200).get(0).path("status").asText());
        receipt(f.part(),"S2","S3","S4");
        assertEquals(1,admin.read(admin.call("GET","/warehouse/preparations",null,false),200).get(0).path("missing").asInt());
        admin.read(admin.call("POST",path+"/reserve",Map.of("version",3),true),200);
        assertEquals("FULFILLED",admin.read(admin.call("GET","/warehouse/preparations",null,false),200).get(0).path("status").asText());
        admin.read(admin.call("POST",path+"/preparation",Map.of("version",4,"requirementId",c.path("requirementId").asText(),"notes",""),true),409);
        String piece=stock(oid).path("pieces").get(0).path("id").asText();
        admin.read(admin.call("POST",path+"/issue",Map.of("version",4,"pieceIds",List.of(piece),"notes",""),true),200);
        admin.read(admin.call("POST",path+"/pieces/"+piece+"/return",Map.of("version",5,"condition","GOOD","notes","Unused and inspected"),true),200);
        assertEquals("REQUESTED",admin.read(admin.call("GET","/warehouse/preparations",null,false),200).get(0).path("status").asText());
        admin.read(admin.call("POST","/manufacturing-orders/"+oid+"/cancel",Map.of("version",6,"reason","Cancel preparation"),true),200);
        assertEquals("CANCELLED",admin.read(admin.call("GET","/warehouse/preparations",null,false),200).get(0).path("status").asText());
    }
    @Test void assignedStockViewsDoNotGrantWarehouseOrMutationAccess() throws Exception {
        var f=orderFixture(); var ui=input("stock-worker@test.local",List.of(),Map.of("orders.view-assigned",true));
        String uid=admin.read(admin.call("POST","/users",ui,true),201).path("id").asText();
        db.sql("UPDATE users SET must_change_password=false WHERE email='stock-worker@test.local'").update();
        var p=orderInput("mine",f); p.put("responsibleId",uid); String mine=newOrder(p).path("id").asText();
        String other=openStockOrder(f,"other").path("id").asText();
        Client worker=new Client(); worker.login("stock-worker@test.local",INITIAL);
        worker.read(worker.call("GET","/manufacturing-orders/"+mine+"/stock",null,false),200);
        worker.read(worker.call("GET","/manufacturing-orders/"+other+"/stock",null,false),404);
        for(String endpoint:List.of("/warehouse/pieces","/warehouse/balances","/warehouse/movements","/warehouse/preparations")) worker.read(worker.call("GET",endpoint,null,false),403);
        worker.read(worker.call("POST","/warehouse/receipts",receiptInput(f.part(),"denied"),true),403);
        worker.read(worker.call("POST","/manufacturing-orders/"+mine+"/stock/reserve",Map.of("version",0),true),403);
        ui.put("overrides",Map.of("stock.view",true,"stock.issue",true));
        admin.read(admin.call("PUT","/users/"+uid,ui,true),200);
        worker.read(worker.call("GET","/warehouse/balances",null,false),200);
        worker.read(worker.call("POST","/manufacturing-orders/"+other+"/stock/issue",Map.of("version",1,"pieceIds",List.of(UUID.randomUUID().toString()),"notes",""),true),403);
    }
    @Test void concurrentIssueHasOneWinnerAndNoDuplicateMovement() throws Exception {
        var f=orderFixture(); receipt(f.part(),"S1"); String oid=openStockOrder(f,"race").path("id").asText();
        String piece=stock(oid).path("pieces").get(0).path("id").asText(), path="/manufacturing-orders/"+oid+"/stock/issue";
        var body=Map.of("version",1,"pieceIds",List.of(piece),"notes","");
        var a=CompletableFuture.supplyAsync(() -> catalogStatus("POST",path,body));
        var b=CompletableFuture.supplyAsync(() -> catalogStatus("POST",path,body));
        assertEquals(Set.of(200,409),Set.of(a.join(),b.join()));
        assertEquals(1L,db.sql("SELECT count(*) FROM stock_movements WHERE action='ISSUED'").query(Long.class).single());
    }
    @Test void concurrentReceiptsCannotRegisterTheSamePhysicalSerialTwice() throws Exception {
        String pid=part("p").path("id").asText(); var body=receiptInput(pid,"S1");
        var a=CompletableFuture.supplyAsync(() -> catalogStatus("POST","/warehouse/receipts",body));
        var b=CompletableFuture.supplyAsync(() -> catalogStatus("POST","/warehouse/receipts",body));
        assertEquals(Set.of(201,409),Set.of(a.join(),b.join()));
        assertEquals(1L,db.sql("SELECT count(*) FROM stock_pieces").query(Long.class).single());
    }
    JsonNode rawPart(String code,String unit) throws Exception {
        var p=partInput(code); p.put("kind","MEASURED_STOCK"); p.put("stockUnit",unit); p.put("defaultInstallMinutes",0);
        return admin.read(admin.call("POST","/parts",p,true),201);
    }
    Map<String,Object> rawReceipt(String part,String code,Number length,Number width) {
        return Map.of("partId",part,"codes",List.of(code),"lengthMm",length,"widthMm",width,"location","Raw shelf","reference","Test receipt","notes","");
    }
    JsonNode rawReceive(String part,String code,Number length,Number width) throws Exception {
        return admin.read(admin.call("POST","/warehouse/raw/receipts",rawReceipt(part,code,length,width),true),201).get(0);
    }
    JsonNode preparationFor(String order) throws Exception {
        var s=stock(order); var r=s.path("requirements").get(0);
        admin.read(admin.call("POST","/manufacturing-orders/"+order+"/stock/preparation",Map.of("version",s.path("version").asLong(),"requirementId",r.path("requirementId").asText(),"notes","Cut missing pieces"),true),200);
        var q=admin.read(admin.call("GET","/warehouse/preparations",null,false),200).get(0);
        admin.read(admin.call("POST","/warehouse/preparations/"+q.path("id").asText()+"/start",Map.of("version",q.path("version").asLong()),true),200);
        return rawPreparation(q.path("id").asText());
    }
    JsonNode rawPreparation(String id) throws Exception {
        for(var q:admin.read(admin.call("GET","/warehouse/preparations",null,false),200)) if(q.path("id").asText().equals(id)) return q;
        throw new AssertionError("Missing preparation");
    }
    JsonNode rawIssue(JsonNode u,JsonNode q) throws Exception {
        return admin.read(admin.call("POST","/warehouse/raw/units/"+u.path("id").asText()+"/issue",Map.of("version",u.path("version").asLong(),"preparationId",q.path("id").asText(),"preparationVersion",q.path("version").asLong(),"notes",""),true),200);
    }
    Map<String,Object> cutInput(JsonNode u,JsonNode q,String...serials) {
        return new HashMap<>(Map.of("version",u.path("version").asLong(),"preparationVersion",q.path("version").asLong(),"serials",List.of(serials),"columns",1,"rotated",false,"returnFirst",true,"returnSecond",true,"notes",""));
    }
    @Test void measuredReceiptsAreAtomicAndLockSpecificationsAndProfiles() throws Exception {
        var raw=rawPart("bar","LENGTH"); String id=raw.path("id").asText(); rawReceive(id,"r1",500,60);
        var body=new HashMap<>(rawReceipt(id,"r2",500,60)); body.put("codes",List.of("r2","R1"));
        admin.read(admin.call("POST","/warehouse/raw/receipts",body,true),409);
        body.put("codes",List.of("r2","R2")); admin.read(admin.call("POST","/warehouse/raw/receipts",body,true),409);
        admin.read(admin.call("POST","/warehouse/raw/receipts",rawReceipt(id,"wrong-width",500,61),true),409);
        admin.read(admin.call("POST","/warehouse/raw/receipts",rawReceipt(id,"negative",-1,60),true),400);
        admin.read(admin.call("POST","/warehouse/raw/receipts",rawReceipt(part("ready").path("id").asText(),"ready",500,60),true),409);
        var spec=partInput("bar"); spec.put("kind","MEASURED_STOCK"); spec.put("stockUnit","LENGTH"); spec.put("defaultInstallMinutes",0); spec.put("widthMm",61);
        assertEquals("SPECIFICATION_IN_USE",admin.read(admin.call("PUT","/parts/"+id,spec,true),409).path("code").asText());
        assertEquals(1L,db.sql("SELECT count(*) FROM raw_units").query(Long.class).single());
        assertEquals(1L,db.sql("SELECT count(*) FROM raw_movements").query(Long.class).single());
        assertEquals(0,new java.math.BigDecimal("500").compareTo(admin.read(admin.call("GET","/warehouse/raw/balances",null,false),200).get(0).path("available").decimalValue()));
    }
    @Test void lengthCutAllocatesOnlyItsPreparationAndReturnsAllRemnantsWithoutKerf() throws Exception {
        var f=orderFixture(); String oid=openStockOrder(f,"raw-order").path("id").asText(); var q=preparationFor(oid);
        String raw=rawPart("bar","LENGTH").path("id").asText(); var u=rawIssue(rawReceive(raw,"B1",500,60),q); q=rawPreparation(q.path("id").asText());
        String path="/warehouse/raw/units/"+u.path("id").asText(); var p=cutInput(u,q,"CUT1","CUT2");
        var preview=admin.read(admin.call("POST",path+"/cut-preview",p,true),200);
        assertEquals(0,new java.math.BigDecimal("259.750").compareTo(preview.path("returnedAmount").decimalValue()));
        assertEquals(1L,db.sql("SELECT count(*) FROM raw_units").query(Long.class).single());
        admin.read(admin.call("POST",path+"/cut",p,true),200);
        var s=stock(oid); assertEquals(2,s.path("pieces").size()); assertEquals(2,s.path("requirements").get(0).path("reserved").asInt()); assertEquals(2,s.path("requirements").get(1).path("missing").asInt());
        assertEquals("FULFILLED",rawPreparation(q.path("id").asText()).path("status").asText());
        assertEquals(1L,db.sql("SELECT count(*) FROM raw_units WHERE status='CONSUMED'").query(Long.class).single());
        assertEquals(1L,db.sql("SELECT count(*) FROM raw_units WHERE status='AVAILABLE' AND parent_id IS NOT NULL").query(Long.class).single());
        assertEquals(2,admin.read(admin.call("GET","/warehouse/raw/cuts",null,false),200).get(0).path("serials").size());
        admin.read(admin.call("POST",path+"/cut",p,true),409);
        admin.read(admin.call("POST","/manufacturing-orders/"+oid+"/cancel",Map.of("version",s.path("version").asLong(),"reason","Done"),true),200);
    }
    @Test void cuttingRejectsOverproductionInvalidGeometryAndDuplicateSerialsAtomically() throws Exception {
        var f=orderFixture(); String oid=openStockOrder(f,"raw-invalid").path("id").asText(); var q=preparationFor(oid);
        String raw=rawPart("sheet","AREA").path("id").asText(); var u=rawIssue(rawReceive(raw,"SHEET",200,100),q); q=rawPreparation(q.path("id").asText());
        String path="/warehouse/raw/units/"+u.path("id").asText()+"/cut";
        admin.read(admin.call("POST",path,cutInput(u,q,"ONE","TWO","THREE"),true),409);
        admin.read(admin.call("POST",path,cutInput(u,q,"ONE","TWO"),true),409);
        var p=cutInput(u,q,"ONE"); p.put("rotated",true); admin.read(admin.call("POST",path,p,true),409);
        receipt(f.part(),"DUPLICATE");
        admin.read(admin.call("POST",path,cutInput(u,q,"duplicate"),true),409);
        assertEquals(0L,db.sql("SELECT count(*) FROM raw_cuts").query(Long.class).single());
        assertEquals(1L,db.sql("SELECT count(*) FROM raw_units WHERE status='ISSUED'").query(Long.class).single());
        assertEquals(0,stock(oid).path("pieces").size());
        p=cutInput(u,q,"VALID"); p.put("returnFirst",false);
        admin.read(admin.call("POST",path,p,true),409); p.put("notes","Damaged end; side is reusable");
        var cut=admin.read(admin.call("POST",path,p,true),200); assertTrue(cut.path("scrapAmount").doubleValue()>0); assertTrue(cut.path("returnedAmount").doubleValue()>0);
        assertEquals(1L,db.sql("SELECT count(*) FROM raw_units WHERE status='SCRAPPED'").query(Long.class).single());
        assertEquals(1L,db.sql("SELECT count(*) FROM raw_units WHERE status='AVAILABLE'").query(Long.class).single());
    }
    @Test void rawIssueVersionsAndReturnsProtectCancellationAndRejectInactivePreparation() throws Exception {
        var f=orderFixture(); String oid=openStockOrder(f,"raw-return").path("id").asText(); var q=preparationFor(oid);
        String raw=rawPart("bar","LENGTH").path("id").asText(); var u=rawReceive(raw,"B",500,60); var issued=rawIssue(u,q);
        admin.read(admin.call("POST","/warehouse/raw/units/"+u.path("id").asText()+"/issue",Map.of("version",0,"preparationId",q.path("id").asText(),"preparationVersion",q.path("version").asLong(),"notes",""),true),409);
        String path="/manufacturing-orders/"+oid+"/cancel";
        assertEquals("RAW_RETURN_BEFORE_CANCEL",admin.read(admin.call("POST",path,Map.of("version",stock(oid).path("version").asLong(),"reason","Cancel"),true),409).path("code").asText());
        admin.read(admin.call("POST","/warehouse/raw/units/"+u.path("id").asText()+"/return",Map.of("version",0,"notes","Unused"),true),409);
        var returned=admin.read(admin.call("POST","/warehouse/raw/units/"+u.path("id").asText()+"/return",Map.of("version",issued.path("version").asLong(),"notes","Unused"),true),200);
        assertEquals("AVAILABLE",returned.path("status").asText());
        admin.read(admin.call("POST",path,Map.of("version",stock(oid).path("version").asLong(),"reason","Returned"),true),200);
        admin.read(admin.call("POST","/warehouse/raw/units/"+u.path("id").asText()+"/issue",Map.of("version",returned.path("version").asLong(),"preparationId",q.path("id").asText(),"preparationVersion",rawPreparation(q.path("id").asText()).path("version").asLong(),"notes",""),true),409);
    }
    @Test void concurrentCutHasOneWinnerAndNoDuplicateOutputsOrRemnants() throws Exception {
        var f=orderFixture(); String oid=openStockOrder(f,"cut-race").path("id").asText(); var q=preparationFor(oid);
        String raw=rawPart("bar","LENGTH").path("id").asText(); var u=rawIssue(rawReceive(raw,"B",500,60),q); q=rawPreparation(q.path("id").asText());
        String path="/warehouse/raw/units/"+u.path("id").asText()+"/cut"; var p=cutInput(u,q,"S1","S2");
        var a=CompletableFuture.supplyAsync(()->catalogStatus("POST",path,p)); var b=CompletableFuture.supplyAsync(()->catalogStatus("POST",path,p));
        assertEquals(Set.of(200,409),Set.of(a.join(),b.join()));
        assertEquals(1L,db.sql("SELECT count(*) FROM raw_cuts").query(Long.class).single()); assertEquals(2L,db.sql("SELECT count(*) FROM raw_units").query(Long.class).single());
        assertEquals(2L,db.sql("SELECT count(*) FROM raw_cut_outputs").query(Long.class).single());
    }
    @Test void fulfilledPreparationCannotConsumeIssuedRawButUnusedSourcesRemainReturnable() throws Exception {
        var f=orderFixture(); String oid=openStockOrder(f,"raw-covered").path("id").asText(); var q=preparationFor(oid);
        var wrong=partInput("wrong-profile"); wrong.put("kind","MEASURED_STOCK"); wrong.put("stockUnit","LENGTH"); wrong.put("defaultInstallMinutes",0); wrong.put("widthMm",61);
        String badId=admin.read(admin.call("POST","/parts",wrong,true),201).path("id").asText(); var bad=rawReceive(badId,"BAD",500,61);
        assertEquals("RAW_PROFILE_MISMATCH",admin.read(admin.call("POST","/warehouse/raw/units/"+bad.path("id").asText()+"/issue",Map.of("version",0,"preparationId",q.path("id").asText(),"preparationVersion",q.path("version").asLong(),"notes",""),true),409).path("code").asText());
        String raw=rawPart("bar","LENGTH").path("id").asText(); var u=rawIssue(rawReceive(raw,"B",500,60),q);
        receipt(f.part(),"EXTERNAL1","EXTERNAL2");
        admin.read(admin.call("POST","/manufacturing-orders/"+oid+"/stock/reserve",Map.of("version",stock(oid).path("version").asLong()),true),200);
        q=rawPreparation(q.path("id").asText()); assertEquals("FULFILLED",q.path("status").asText());
        String path="/warehouse/raw/units/"+u.path("id").asText();
        assertEquals("RAW_PREPARATION_NOT_ACTIVE",admin.read(admin.call("POST",path+"/cut",cutInput(u,q,"UNNEEDED"),true),409).path("code").asText());
        var archive=partInput("bar"); archive.put("kind","MEASURED_STOCK"); archive.put("stockUnit","LENGTH"); archive.put("defaultInstallMinutes",0); archive.put("active",false);
        admin.read(admin.call("PUT","/parts/"+raw,archive,true),200);
        admin.read(admin.call("POST","/warehouse/raw/receipts",rawReceipt(raw,"INACTIVE",500,60),true),409);
        admin.read(admin.call("POST",path+"/return",Map.of("version",u.path("version").asLong(),"notes","No longer needed; physically inspected and returned"),true),200);
        assertEquals(0L,db.sql("SELECT count(*) FROM raw_cuts").query(Long.class).single());
    }
    @Test void rawPermissionsRequireReadAndGlobalOrderAccessAndApplyLiveDenials() throws Exception {
        var raw=rawPart("raw","LENGTH"); var body=rawReceipt(raw.path("id").asText(),"B",500,60);
        var ui=input("raw-worker@test.local",List.of(),Map.of("raw.receive",true,"raw.issue",true,"raw.cut",true));
        String uid=admin.read(admin.call("POST","/users",ui,true),201).path("id").asText(); db.sql("UPDATE users SET must_change_password=false WHERE id=:id").param("id",UUID.fromString(uid)).update();
        Client worker=new Client(); worker.login("raw-worker@test.local",INITIAL);
        for(String endpoint:List.of("units","balances","movements","cuts")) worker.read(worker.call("GET","/warehouse/raw/"+endpoint,null,false),403);
        worker.read(worker.call("POST","/warehouse/raw/receipts",body,true),403);
        ui.put("overrides",Map.of("stock.view",true,"catalog.view",true,"raw.receive",true,"raw.issue",true,"raw.cut",true));
        admin.read(admin.call("PUT","/users/"+uid,ui,true),200); var u=worker.read(worker.call("POST","/warehouse/raw/receipts",body,true),201).get(0);
        String path="/warehouse/raw/units/"+u.path("id").asText();
        worker.read(worker.call("POST",path+"/issue",Map.of("version",0,"preparationId",UUID.randomUUID().toString(),"preparationVersion",0,"notes",""),true),403);
        worker.read(worker.call("POST",path+"/cut",Map.of("version",0,"preparationVersion",0,"serials",List.of("S"),"columns",1,"rotated",false,"returnFirst",true,"returnSecond",true,"notes",""),true),403);
        ui.put("version",1); ui.put("overrides",Map.of("stock.view",true,"catalog.view",true,"raw.receive",false));
        admin.read(admin.call("PUT","/users/"+uid,ui,true),200); worker.read(worker.call("POST","/warehouse/raw/receipts",body,true),403);
    }
    record Worker(String id,Client client) {}
    Worker assemblyWorker(String name) throws Exception {
        String email=name+"@test.local"; var ui=input(email,List.of(),Map.of("tasks.view-assigned",true,"tasks.work",true,"tasks.uninstall",true,"tasks.reject",true));
        String id=admin.read(admin.call("POST","/users",ui,true),201).path("id").asText();
        db.sql("UPDATE users SET must_change_password=false WHERE id=:id").param("id",UUID.fromString(id)).update();
        Client c=new Client(); c.login(email,INITIAL); return new Worker(id,c);
    }
    JsonNode assemblyTasks(String order) throws Exception { return admin.read(admin.call("GET","/assembly/tasks?orderId="+order,null,false),200); }
    JsonNode assemblyTask(String id) throws Exception { return admin.read(admin.call("GET","/assembly/tasks/"+id,null,false),200).path("task"); }
    JsonNode assignTask(JsonNode task,String worker) throws Exception {
        var p=new HashMap<String,Object>(); p.put("version",task.path("version").asLong()); p.put("workerId",worker); p.put("notes","Manager assignment");
        return admin.read(admin.call("POST","/assembly/tasks/"+task.path("id").asText()+"/assign",p,true),200).path("task");
    }
    JsonNode issueTo(String order,String worker,List<String> pieces) throws Exception {
        return admin.read(admin.call("POST","/manufacturing-orders/"+order+"/stock/issue",Map.of("version",stock(order).path("version").asLong(),"recipientId",worker,"pieceIds",pieces,"notes","Issued to assigned worker"),true),200);
    }
    JsonNode taskAction(Worker worker,String id,String action) throws Exception {
        var t=assemblyTask(id); return worker.client().read(worker.client().call("POST","/assembly/tasks/"+id+"/"+action,Map.of("version",t.path("version").asLong(),"notes","Assembly operation","reason","Inspection removal"),true),200).path("task");
    }
    @Test void releaseCreatesIndependentTasksForEveryRequirementOccurrence() throws Exception {
        var f=orderFixture(); String id=newOrder(orderInput("tasks",f)).path("id").asText(); assertEquals(0,assemblyTasks(id).size());
        admin.read(admin.call("POST","/manufacturing-orders/"+id+"/release",Map.of("version",0),true),200);
        var tasks=assemblyTasks(id); assertEquals(4,tasks.size());
        assertEquals("A1",tasks.get(0).path("positionCode").asText()); assertEquals(1,tasks.get(0).path("occurrence").asInt());
        assertEquals("A1",tasks.get(1).path("positionCode").asText()); assertEquals(2,tasks.get(1).path("occurrence").asInt());
        assertEquals("A2",tasks.get(2).path("positionCode").asText()); assertTrue(tasks.get(0).path("workerId").isNull());
        assertEquals(0,admin.read(admin.call("GET","/machines/"+f.machine()+"/assembly",null,false),200).path("installations").size());
    }
    @Test void workersSeeOnlyTheirTasksNotOrdersWarehouseOtherWorkersOrMachineHistory() throws Exception {
        var f=orderFixture(); String oid=openStockOrder(f,"worker-scope").path("id").asText(); var w1=assemblyWorker("worker-one"); var w2=assemblyWorker("worker-two");
        var tasks=assemblyTasks(oid); var t1=assignTask(tasks.get(0),w1.id()); var t2=assignTask(tasks.get(1),w2.id());
        var own=w1.client().read(w1.client().call("GET","/assembly/tasks",null,false),200); assertEquals(1,own.size()); assertEquals(t1.path("id").asText(),own.get(0).path("id").asText());
        String path="/assembly/tasks/"+t2.path("id").asText();
        w1.client().read(w1.client().call("GET",path,null,false),404);
        w1.client().read(w1.client().call("POST",path+"/start",Map.of("version",t2.path("version").asLong(),"notes",""),true),404);
        for(String denied:List.of("/warehouse/pieces","/manufacturing-orders/"+oid,"/machines/"+f.machine()+"/assembly","/assembly/workers")) w1.client().read(w1.client().call("GET",denied,null,false),403);
        var moved=assignTask(t1,w2.id()); assertEquals(w2.id(),moved.path("workerId").asText());
        w1.client().read(w1.client().call("GET","/assembly/tasks/"+t1.path("id").asText(),null,false),404);
        assertEquals(0,w1.client().read(w1.client().call("GET","/assembly/tasks",null,false),200).size());
        assertEquals(2,w2.client().read(w2.client().call("GET","/assembly/tasks?orderId="+oid,null,false),200).size());
    }
    @Test void workerIssueIsAtomicAndCannotOverfillAssignmentsOrUseResponsibleFallback() throws Exception {
        var f=orderFixture(); receipt(f.part(),"S1","S2"); String oid=openStockOrder(f,"worker-issue").path("id").asText();
        var w1=assemblyWorker("one"); var w2=assemblyWorker("two"); var tasks=assemblyTasks(oid); assignTask(tasks.get(0),w1.id()); assignTask(tasks.get(1),w2.id());
        var s=stock(oid); var ids=List.of(s.path("pieces").get(0).path("id").asText(),s.path("pieces").get(1).path("id").asText()); String path="/manufacturing-orders/"+oid+"/stock/issue";
        admin.read(admin.call("POST",path,Map.of("version",s.path("version").asLong(),"pieceIds",ids,"recipientId",w1.id(),"notes",""),true),409);
        assertEquals(0L,db.sql("SELECT count(*) FROM stock_pieces WHERE task_id IS NOT NULL").query(Long.class).single());
        assertEquals(0L,db.sql("SELECT count(*) FROM assembly_events WHERE action='PIECE_ISSUED'").query(Long.class).single());
        admin.read(admin.call("POST",path,Map.of("version",s.path("version").asLong(),"pieceIds",List.of(ids.getFirst()),"notes",""),true),409);
        issueTo(oid,w1.id(),List.of(ids.getFirst())); issueTo(oid,w2.id(),List.of(ids.get(1)));
        assertEquals(2L,db.sql("SELECT count(*) FROM stock_pieces WHERE status='ISSUED' AND task_id IS NOT NULL").query(Long.class).single());
        var t=assemblyTask(tasks.get(0).path("id").asText());
        assertEquals("TASK_NOT_YOURS",admin.read(admin.call("POST","/assembly/tasks/"+t.path("id").asText()+"/start",Map.of("version",t.path("version").asLong(),"notes",""),true),409).path("code").asText());
    }
    @Test void installationCompletionWaitsForOperationsAndRemovalPreservesHistoryAndInventory() throws Exception {
        var f=orderFixture(); receipt(f.part(),"S1","S2","S3","S4"); String oid=openStockOrder(f,"assembly-cycle").path("id").asText(); var w=assemblyWorker("assembler");
        var tasks=assemblyTasks(oid); for(var t:tasks) assignTask(t,w.id());
        List<String> pieces=new ArrayList<>(); for(var p:stock(oid).path("pieces")) pieces.add(p.path("id").asText()); issueTo(oid,w.id(),pieces);
        String first=tasks.get(0).path("id").asText(); var unstarted=assemblyTask(first);
        w.client().read(w.client().call("POST","/assembly/tasks/"+first+"/install",Map.of("version",unstarted.path("version").asLong(),"notes",""),true),409);
        for(var t:tasks) { String id=t.path("id").asText(); taskAction(w,id,"start"); taskAction(w,id,"install"); }
        String path="/manufacturing-orders/"+oid; var o=admin.read(admin.call("GET",path,null,false),200);
        assertEquals("AWAITING_TESTING",o.path("status").asText()); assertEquals(4,o.path("installedCount").asInt()); assertEquals(4,o.path("configurationVersion").asInt()); assertFalse(o.path("assemblyCompletedAt").isNull());
        var balance=admin.read(admin.call("GET","/warehouse/balances",null,false),200).get(0); assertEquals(4,balance.path("installed").asInt()); assertEquals(0,balance.path("issued").asInt()); assertEquals(0,balance.path("available").asInt());
        assertEquals("TASK_UNINSTALL_BEFORE_CANCEL",admin.read(admin.call("POST",path+"/cancel",Map.of("version",o.path("version").asLong(),"reason","Cancel installed machine"),true),409).path("code").asText());
        String installedPiece=assemblyTask(first).path("pieceId").asText();
        admin.read(admin.call("POST",path+"/stock/pieces/"+installedPiece+"/return",Map.of("version",stock(oid).path("version").asLong(),"condition","GOOD","notes","Still installed"),true),409);
        var current=assemblyTask(first); w.client().read(w.client().call("POST","/assembly/tasks/"+first+"/uninstall",Map.of("version",current.path("version").asLong(),"reason",""),true),400);
        taskAction(w,first,"uninstall"); o=admin.read(admin.call("GET",path,null,false),200); assertEquals("IN_PROGRESS",o.path("status").asText()); assertEquals(5,o.path("configurationVersion").asInt()); assertTrue(o.path("assemblyCompletedAt").isNull());
        admin.read(admin.call("POST",path+"/stock/pieces/"+installedPiece+"/return",Map.of("version",stock(oid).path("version").asLong(),"condition","GOOD","notes","Inspected and returned"),true),200);
        assertEquals("PENDING",assemblyTask(first).path("status").asText()); assertTrue(assemblyTask(first).path("pieceId").isNull());
        admin.read(admin.call("POST",path+"/stock/reserve",Map.of("version",stock(oid).path("version").asLong()),true),200); issueTo(oid,w.id(),List.of(installedPiece)); taskAction(w,first,"start"); taskAction(w,first,"install");
        var history=admin.read(admin.call("GET","/machines/"+f.machine()+"/assembly",null,false),200); assertEquals(6,history.path("configurationVersion").asInt()); assertEquals(5,history.path("installations").size());
        assertEquals(1L,db.sql("SELECT count(*) FROM installations WHERE removed_at IS NOT NULL").query(Long.class).single());
        assertEquals(4L,db.sql("SELECT count(*) FROM installations WHERE removed_at IS NULL").query(Long.class).single());
        assertEquals(5L,db.sql("SELECT count(*) FROM stock_movements WHERE action='INSTALLED'").query(Long.class).single());
    }
    @Test void concurrentInstallHasOneWinnerAndOneConfigurationChange() throws Exception {
        var f=orderFixture(); receipt(f.part(),"S1"); String oid=openStockOrder(f,"install-race").path("id").asText(); var w=assemblyWorker("race");
        var t=assignTask(assemblyTasks(oid).get(0),w.id()); issueTo(oid,w.id(),List.of(stock(oid).path("pieces").get(0).path("id").asText())); t=taskAction(w,t.path("id").asText(),"start");
        String path="/assembly/tasks/"+t.path("id").asText()+"/install"; var body=Map.of("version",t.path("version").asLong(),"notes","");
        var a=CompletableFuture.supplyAsync(()->{ try{return w.client().call("POST",path,body,true).statusCode();}catch(Exception e){throw new RuntimeException(e);} });
        var b=CompletableFuture.supplyAsync(()->{ try{return w.client().call("POST",path,body,true).statusCode();}catch(Exception e){throw new RuntimeException(e);} });
        assertEquals(Set.of(200,409),Set.of(a.join(),b.join())); assertEquals(1L,db.sql("SELECT count(*) FROM installations").query(Long.class).single());
        assertEquals(1L,db.sql("SELECT configuration_version FROM machines WHERE id=:id").param("id",UUID.fromString(f.machine())).query(Long.class).single());
    }
    @Test void anotherOrderCannotOccupyAnInstalledMachineSlot() throws Exception {
        var f=orderFixture(); receipt(f.part(),"S1","S2","S3","S4"); String first=openStockOrder(f,"slot-one").path("id").asText(); var w=assemblyWorker("slot-worker");
        var t=assignTask(assemblyTasks(first).get(0),w.id()); issueTo(first,w.id(),List.of(stock(first).path("pieces").get(0).path("id").asText())); taskAction(w,t.path("id").asText(),"start"); taskAction(w,t.path("id").asText(),"install");
        receipt(f.part(),"S5","S6","S7","S8"); String second=openStockOrder(f,"slot-two").path("id").asText(); var other=assignTask(assemblyTasks(second).get(0),w.id());
        issueTo(second,w.id(),List.of(stock(second).path("pieces").get(0).path("id").asText())); other=taskAction(w,other.path("id").asText(),"start");
        assertEquals("TASK_SLOT_OCCUPIED",w.client().read(w.client().call("POST","/assembly/tasks/"+other.path("id").asText()+"/install",Map.of("version",other.path("version").asLong(),"notes",""),true),409).path("code").asText());
        assertEquals("ISSUED",assemblyTask(other.path("id").asText()).path("pieceStatus").asText()); assertEquals(1L,db.sql("SELECT count(*) FROM installations").query(Long.class).single());
    }
    @Test void assignmentsRequireReturnVersionsEligibilityAndReassignmentReasons() throws Exception {
        var f=orderFixture(); receipt(f.part(),"S1"); String oid=openStockOrder(f,"assignment-guards").path("id").asText(); var w=assemblyWorker("guards"); var t=assemblyTasks(oid).get(0); String path="/assembly/tasks/"+t.path("id").asText()+"/assign";
        var s=stock(oid); String piece=s.path("pieces").get(0).path("id").asText();
        admin.read(admin.call("POST","/manufacturing-orders/"+oid+"/stock/issue",Map.of("version",s.path("version").asLong(),"pieceIds",List.of(piece),"notes","Legacy custody"),true),200);
        admin.read(admin.call("POST",path,Map.of("version",0,"workerId",w.id(),"notes",""),true),409);
        admin.read(admin.call("POST","/manufacturing-orders/"+oid+"/stock/pieces/"+piece+"/return",Map.of("version",stock(oid).path("version").asLong(),"condition","GOOD","notes","Legacy return"),true),200);
        t=assignTask(t,w.id()); admin.read(admin.call("POST",path,Map.of("version",0,"workerId",w.id(),"notes",""),true),409);
        var clear=new HashMap<String,Object>(); clear.put("version",t.path("version").asLong()); clear.put("workerId",null); clear.put("notes","");
        assertEquals("TASK_ASSIGN_REASON_REQUIRED",admin.read(admin.call("POST",path,clear,true),409).path("code").asText());
        clear.put("notes","Reallocated"); admin.read(admin.call("POST",path,clear,true),200);
        var user=input("guards@test.local",List.of(),Map.of("tasks.view-assigned",true)); admin.read(admin.call("PUT","/users/"+w.id(),user,true),200);
        assertEquals("TASK_WORKER_INVALID",admin.read(admin.call("POST",path,Map.of("version",assemblyTask(t.path("id").asText()).path("version").asLong(),"workerId",w.id(),"notes",""),true),409).path("code").asText());
        w.client().read(w.client().call("POST","/assembly/tasks/"+t.path("id").asText()+"/start",Map.of("version",2,"notes",""),true),403);
    }
    @Test void cancelledTasksRemainReadableButCannotBeWorkedOrReassigned() throws Exception {
        var f=orderFixture(); String oid=openStockOrder(f,"cancel-tasks").path("id").asText(); var w=assemblyWorker("cancel-worker"); var t=assignTask(assemblyTasks(oid).get(0),w.id());
        admin.read(admin.call("POST","/manufacturing-orders/"+oid+"/cancel",Map.of("version",stock(oid).path("version").asLong(),"reason","Cancelled before issue"),true),200);
        w.client().read(w.client().call("GET","/assembly/tasks/"+t.path("id").asText(),null,false),200);
        w.client().read(w.client().call("POST","/assembly/tasks/"+t.path("id").asText()+"/start",Map.of("version",t.path("version").asLong(),"notes",""),true),409);
        admin.read(admin.call("POST","/assembly/tasks/"+t.path("id").asText()+"/assign",Map.of("version",t.path("version").asLong(),"workerId",w.id(),"notes",""),true),409);
    }
    record DefectFixture(String order,String task,String piece,Worker worker) {}
    DefectFixture defectFixture(String name) throws Exception {
        var f=orderFixture(); receipt(f.part(),"DEFECT-S1"); String order=openStockOrder(f,name).path("id").asText(); var worker=assemblyWorker(name);
        var task=assignTask(assemblyTasks(order).get(0),worker.id()); String piece=stock(order).path("pieces").get(0).path("id").asText();
        issueTo(order,worker.id(),List.of(piece)); return new DefectFixture(order,task.path("id").asText(),piece,worker);
    }
    JsonNode reject(DefectFixture f) throws Exception {
        return f.worker().client().read(f.worker().client().call("POST","/assembly/tasks/"+f.task()+"/reject",Map.of("version",assemblyTask(f.task()).path("version").asLong(),"reason","Cracked mounting face"),true),200).path("defect");
    }
    JsonNode defectAction(JsonNode d,String action) throws Exception {
        return admin.read(admin.call("POST","/defects/"+d.path("id").asText()+"/"+action,Map.of("version",d.path("version").asLong(),"notes","Inspected physical piece: "+action,"location","Quarantine shelf"),true),200).path("defect");
    }
    @Test void rejectionBlocksUseRetainsCustodyAndReopensShortageUntilPhysicalReceipt() throws Exception {
        var f=defectFixture("reject-custody"); taskAction(f.worker(),f.task(),"start"); var d=reject(f);
        var task=assemblyTask(f.task()); assertEquals("REJECTED",task.path("pieceStatus").asText()); assertEquals("PENDING",task.path("status").asText()); assertEquals(f.piece(),task.path("pieceId").asText());
        assertEquals(2,stock(f.order()).path("requirements").get(0).path("missing").asInt());
        for(String action:List.of("start","install","reject")) f.worker().client().read(f.worker().client().call("POST","/assembly/tasks/"+f.task()+"/"+action,Map.of("version",task.path("version").asLong(),"notes","blocked","reason","blocked"),true),409);
        var s=stock(f.order()); admin.read(admin.call("POST","/manufacturing-orders/"+f.order()+"/stock/pieces/"+f.piece()+"/return",Map.of("version",s.path("version").asLong(),"condition","GOOD","notes","Bypass"),true),409);
        assertEquals("DEFECT_RETURN_BEFORE_CANCEL",admin.read(admin.call("POST","/manufacturing-orders/"+f.order()+"/cancel",Map.of("version",s.path("version").asLong(),"reason","Cancel custody"),true),409).path("code").asText());
        admin.read(admin.call("POST","/defects/"+d.path("id").asText()+"/repair-start",Map.of("version",0,"notes","Not received"),true),409);
        d=defectAction(d,"receive"); assertEquals("QUARANTINED",d.path("pieceStatus").asText()); assertTrue(assemblyTask(f.task()).path("pieceId").isNull());
        var p=admin.read(admin.call("GET","/warehouse/pieces",null,false),200).get(0);
        assertEquals("DEFECT_USE_WORKFLOW",admin.read(admin.call("POST","/warehouse/pieces/"+f.piece()+"/restore",Map.of("version",p.path("version").asLong(),"notes","Bypass repair"),true),409).path("code").asText());
    }
    @Test void repairRequiresWarehouseAcceptanceBeforeSameSerialCanBeReservedAndInstalled() throws Exception {
        var f=defectFixture("repair-cycle"); var d=defectAction(reject(f),"receive");
        d=defectAction(d,"repair-start"); assertEquals("IN_REPAIR",d.path("pieceStatus").asText());
        d=defectAction(d,"repair-finish"); assertEquals("REPAIRED",d.path("pieceStatus").asText());
        admin.read(admin.call("POST","/manufacturing-orders/"+f.order()+"/stock/reserve",Map.of("version",stock(f.order()).path("version").asLong()),true),200); assertEquals(0,stock(f.order()).path("pieces").size());
        d=defectAction(d,"accept"); assertEquals("RETURNED",d.path("status").asText()); assertEquals("AVAILABLE",d.path("pieceStatus").asText());
        admin.read(admin.call("POST","/manufacturing-orders/"+f.order()+"/stock/reserve",Map.of("version",stock(f.order()).path("version").asLong()),true),200);
        issueTo(f.order(),f.worker().id(),List.of(f.piece())); taskAction(f.worker(),f.task(),"start"); taskAction(f.worker(),f.task(),"install");
        assertEquals("DEFECT-S1",assemblyTask(f.task()).path("serialNumber").asText());
        assertEquals(5,admin.read(admin.call("GET","/defects/"+d.path("id").asText(),null,false),200).path("events").size());
        taskAction(f.worker(),f.task(),"uninstall"); var again=reject(f); assertNotEquals(d.path("id").asText(),again.path("id").asText());
        assertEquals(2,admin.read(admin.call("GET","/defects",null,false),200).size());
    }
    @Test void scrapIsTerminalPreservesSerialAndDoesNotBlockReplacementOrCancellationAfterReceipt() throws Exception {
        var f=defectFixture("scrap-cycle"); var d=defectAction(reject(f),"receive"); d=defectAction(d,"scrap"); assertEquals("SCRAPPED",d.path("pieceStatus").asText());
        for(String action:List.of("receive","repair-start","repair-finish","accept","scrap")) admin.read(admin.call("POST","/defects/"+d.path("id").asText()+"/"+action,Map.of("version",d.path("version").asLong(),"notes","Repeat","location","Shelf"),true),409);
        String part=db.sql("SELECT part_id FROM stock_pieces WHERE id=:id").param("id",UUID.fromString(f.piece())).query(UUID.class).single().toString();
        admin.read(admin.call("POST","/warehouse/receipts",Map.of("partId",part,"serials",List.of("DEFECT-S1"),"location","","reference","","notes",""),true),409);
        receipt(part,"REPLACEMENT-S2"); admin.read(admin.call("POST","/manufacturing-orders/"+f.order()+"/stock/reserve",Map.of("version",stock(f.order()).path("version").asLong()),true),200);
        var replacement=stock(f.order()).path("pieces").get(0); assertEquals("REPLACEMENT-S2",replacement.path("serialNumber").asText());
        admin.read(admin.call("POST","/manufacturing-orders/"+f.order()+"/cancel",Map.of("version",stock(f.order()).path("version").asLong(),"reason","Cancel after actual return"),true),200);
        var balance=admin.read(admin.call("GET","/warehouse/balances",null,false),200).get(0); assertEquals(1,balance.path("scrapped").asInt()); assertEquals(1,balance.path("available").asInt());
    }
    @Test void defectReportsAreScopedAndManufacturingWarehouseAndScrapPermissionsAreIndependent() throws Exception {
        var f=defectFixture("defect-scope"); var other=assemblyWorker("other-defect"); var d=reject(f); String path="/defects/"+d.path("id").asText();
        assertEquals(1,f.worker().client().read(f.worker().client().call("GET","/defects",null,false),200).size());
        assertEquals(0,other.client().read(other.client().call("GET","/defects",null,false),200).size()); other.client().read(other.client().call("GET",path,null,false),404);
        other.client().read(other.client().call("POST","/assembly/tasks/"+f.task()+"/reject",Map.of("version",0,"reason","Not mine"),true),404);
        for(String action:List.of("receive","repair-start","repair-finish","accept","scrap")) f.worker().client().read(f.worker().client().call("POST",path+"/"+action,Map.of("version",0,"notes","Forbidden","location","Shelf"),true),403);
        var ui=input("repairer@test.local",List.of(),Map.of("defects.view",true,"orders.view",true,"defects.manage",true));
        admin.read(admin.call("POST","/users",ui,true),201); db.sql("UPDATE users SET must_change_password=false WHERE email='repairer@test.local'").update(); var repairer=new Client(); repairer.login("repairer@test.local",INITIAL);
        repairer.read(repairer.call("POST",path+"/receive",Map.of("version",0,"notes","No warehouse permission","location","Shelf"),true),403);
        d=defectAction(d,"receive"); d=repairer.read(repairer.call("POST",path+"/repair-start",Map.of("version",d.path("version").asLong(),"notes","Machining repair"),true),200).path("defect");
        repairer.read(repairer.call("POST",path+"/scrap",Map.of("version",d.path("version").asLong(),"notes","No scrap permission"),true),403);
    }
    @Test void globalTaskViewDoesNotGrantGlobalDefectAccessAndRejectPermissionRevokesLive() throws Exception {
        var f=defectFixture("global-reporter");
        var ui=input("global-reporter@test.local",List.of(),Map.of("tasks.view",true,"orders.view",true,"tasks.work",true,"tasks.reject",true));
        admin.read(admin.call("PUT","/users/"+f.worker().id(),ui,true),200); var d=reject(f);
        assertEquals(1,f.worker().client().read(f.worker().client().call("GET","/defects",null,false),200).size());
        var other=assemblyWorker("global-other"); var second=assignTask(assemblyTasks(f.order()).get(1),other.id());
        String part=db.sql("SELECT part_id FROM stock_pieces WHERE id=:id").param("id",UUID.fromString(f.piece())).query(UUID.class).single().toString();
        receipt(part,"OTHER-SERIAL"); admin.read(admin.call("POST","/manufacturing-orders/"+f.order()+"/stock/reserve",Map.of("version",stock(f.order()).path("version").asLong()),true),200);
        String piece=stock(f.order()).path("pieces").get(1).path("id").asText();
        // Find the reserved replacement independently of serial allocation ordering.
        for(var p:stock(f.order()).path("pieces")) if(p.path("status").asText().equals("RESERVED")) piece=p.path("id").asText();
        issueTo(f.order(),other.id(),List.of(piece));
        var otherCase=other.client().read(other.client().call("POST","/assembly/tasks/"+second.path("id").asText()+"/reject",Map.of("version",assemblyTask(second.path("id").asText()).path("version").asLong(),"reason","Other report"),true),200).path("defect");
        f.worker().client().read(f.worker().client().call("GET","/defects/"+otherCase.path("id").asText(),null,false),404);
        var user=admin.read(admin.call("GET","/users",null,false),200); long version=0; for(var u:user) if(u.path("id").asText().equals(f.worker().id())) version=u.path("version").asLong();
        ui.put("version",version); ui.put("overrides",Map.of("tasks.view",true,"orders.view",true,"tasks.work",true,"tasks.reject",false)); admin.read(admin.call("PUT","/users/"+f.worker().id(),ui,true),200);
        f.worker().client().read(f.worker().client().call("POST","/assembly/tasks/"+f.task()+"/reject",Map.of("version",assemblyTask(f.task()).path("version").asLong(),"reason","Revoked"),true),403);
        assertEquals(d.path("id").asText(),f.worker().client().read(f.worker().client().call("GET","/defects",null,false),200).get(0).path("id").asText());
    }
    @Test void rejectAndDispositionValidateReasonsVersionsAndConcurrentReports() throws Exception {
        var f=defectFixture("reject-race"); var task=assemblyTask(f.task()); String path="/assembly/tasks/"+f.task()+"/reject";
        f.worker().client().read(f.worker().client().call("POST",path,Map.of("version",task.path("version").asLong(),"reason"," "),true),400);
        var body=Map.of("version",task.path("version").asLong(),"reason","Crack");
        var a=CompletableFuture.supplyAsync(()->{ try{return f.worker().client().call("POST",path,body,true).statusCode();}catch(Exception e){throw new RuntimeException(e);} });
        var b=CompletableFuture.supplyAsync(()->{ try{return f.worker().client().call("POST",path,body,true).statusCode();}catch(Exception e){throw new RuntimeException(e);} });
        assertEquals(Set.of(200,409),Set.of(a.join(),b.join())); assertEquals(1L,db.sql("SELECT count(*) FROM defect_cases").query(Long.class).single());
        var d=admin.read(admin.call("GET","/defects",null,false),200).get(0); String dp="/defects/"+d.path("id").asText()+"/receive";
        admin.read(admin.call("POST",dp,Map.of("version",1,"notes","Stale","location","Shelf"),true),409);
        admin.read(admin.call("POST",dp,Map.of("version",0,"notes"," ","location","Shelf"),true),400);
        admin.read(admin.call("POST",dp,Map.of("version",0,"notes","Received","location"," "),true),400);
        assertEquals(1L,db.sql("SELECT count(*) FROM defect_events").query(Long.class).single());
    }
    @Test void installedPieceMustBeRemovedBeforeRejection() throws Exception {
        var f=defectFixture("installed-reject"); taskAction(f.worker(),f.task(),"start"); taskAction(f.worker(),f.task(),"install"); var t=assemblyTask(f.task());
        f.worker().client().read(f.worker().client().call("POST","/assembly/tasks/"+f.task()+"/reject",Map.of("version",t.path("version").asLong(),"reason","Installed defect"),true),409);
        assertEquals(0L,db.sql("SELECT count(*) FROM defect_cases").query(Long.class).single());
        taskAction(f.worker(),f.task(),"uninstall"); reject(f); assertEquals("REJECTED",assemblyTask(f.task()).path("pieceStatus").asText());
    }
    record OperationsFixture(String order,String machine,Worker worker,List<String> tasks) {}
    OperationsFixture operationsFixture() throws Exception {
        var f=orderFixture(); receipt(f.part(),"OP-S1","OP-S2","OP-S3","OP-S4");
        String oid=openStockOrder(f,"operations").path("id").asText(); var worker=assemblyWorker("operator-assembler");
        List<String> tasks=new ArrayList<>(),pieces=new ArrayList<>();
        for(var t:assemblyTasks(oid)) { assignTask(t,worker.id()); tasks.add(t.path("id").asText()); }
        for(var p:stock(oid).path("pieces")) pieces.add(p.path("id").asText()); issueTo(oid,worker.id(),pieces);
        for(String id:tasks) { taskAction(worker,id,"start"); taskAction(worker,id,"install"); }
        return new OperationsFixture(oid,f.machine(),worker,tasks);
    }
    JsonNode operations(String order) throws Exception { return admin.read(admin.call("GET","/operations/orders/"+order,null,false),200); }
    Map<String,Object> startInput(JsonNode detail) {
        var o=detail.path("order"); return new HashMap<>(Map.of("orderVersion",o.path("version").asLong(),"configurationVersion",o.path("configurationVersion").asLong(),
            "checks",List.of(Map.of("label","Trial criterion","criterion","Operator-specified acceptance value"),Map.of("label","Second criterion","criterion","Second acceptance value")),"notes","Initial trial"));
    }
    JsonNode startTrial(String order) throws Exception { return admin.read(admin.call("POST","/operations/orders/"+order+"/start",startInput(operations(order)),true),200); }
    Map<String,Object> finishInput(JsonNode trial,boolean passed) {
        List<Map<String,Object>> checks=new ArrayList<>(); int i=0;
        for(var c:trial.path("checks")) checks.add(new HashMap<>(Map.of("id",c.path("id").asText(),"result",!passed && i++==0?"FAIL":"PASS","notes",passed?"Verified":"Observed deviation")));
        return new HashMap<>(Map.of("version",trial.path("version").asLong(),"outcome",passed?"PASSED":"FAILED","checks",checks,"notes",passed?"All criteria satisfied":"Adjustment required"));
    }
    JsonNode finishTrial(JsonNode trial,boolean passed) throws Exception { return admin.read(admin.call("POST","/operations/trials/"+trial.path("id").asText()+"/finish",finishInput(trial,passed),true),200); }
    @Test void operationsRequiresAssemblyAndValidChecklistAndSnapshotsInstalledSerials() throws Exception {
        var f=orderFixture(); String oid=openStockOrder(f,"not-ready").path("id").asText();
        assertEquals("OPERATIONS_ORDER_STATE",admin.read(admin.call("POST","/operations/orders/"+oid+"/start",startInput(operations(oid)),true),409).path("code").asText());
        // A second fixture needs distinct catalog identities, so use the same open order and assemble it.
        receipt(f.part(),"OP1","OP2","OP3","OP4"); admin.read(admin.call("POST","/manufacturing-orders/"+oid+"/stock/reserve",Map.of("version",stock(oid).path("version").asLong()),true),200);
        var worker=assemblyWorker("ready-worker"); List<String> pieces=new ArrayList<>();
        for(var t:assemblyTasks(oid)) assignTask(t,worker.id()); for(var p:stock(oid).path("pieces")) pieces.add(p.path("id").asText()); issueTo(oid,worker.id(),pieces);
        for(var t:assemblyTasks(oid)) { taskAction(worker,t.path("id").asText(),"start"); taskAction(worker,t.path("id").asText(),"install"); }
        var invalid=startInput(operations(oid)); invalid.put("checks",List.of()); admin.read(admin.call("POST","/operations/orders/"+oid+"/start",invalid,true),400);
        invalid=startInput(operations(oid)); invalid.put("configurationVersion",0); admin.read(admin.call("POST","/operations/orders/"+oid+"/start",invalid,true),409);
        var d=startTrial(oid); var t=d.path("trials").get(0); assertEquals("TESTING",d.path("order").path("status").asText()); assertTrue(d.path("order").path("completedAt").isNull());
        assertEquals(4,t.path("configuration").size()); assertEquals(2,t.path("checks").size()); assertEquals("PENDING",t.path("checks").get(0).path("result").asText()); assertEquals(4,t.path("configurationVersion").asInt());
        admin.read(admin.call("POST","/manufacturing-orders/"+oid+"/cancel",Map.of("version",d.path("order").path("version").asLong(),"reason","Running trial"),true),409);
    }
    @Test void passedTrialCompletesOrderButDeliveryIsSeparateVersionedAndIrreversible() throws Exception {
        var f=operationsFixture(); var t=startTrial(f.order()).path("trials").get(0); var d=finishTrial(t,true); var o=d.path("order");
        assertEquals("COMPLETED",o.path("status").asText()); assertFalse(o.path("completedAt").isNull()); assertFalse(o.path("testedAt").isNull()); assertTrue(o.path("deliveredAt").isNull());
        var order=admin.read(admin.call("GET","/manufacturing-orders/"+f.order(),null,false),200); assertEquals(t.path("id").asText(),order.path("passedTrialId").asText());
        admin.read(admin.call("POST","/operations/trials/"+t.path("id").asText()+"/finish",finishInput(t,true),true),409);
        var task=assemblyTask(f.tasks().getFirst()); f.worker().client().read(f.worker().client().call("POST","/assembly/tasks/"+f.tasks().getFirst()+"/uninstall",Map.of("version",task.path("version").asLong(),"reason","Completed"),true),409);
        String path="/operations/orders/"+f.order()+"/deliver"; admin.read(admin.call("POST",path,Map.of("version",o.path("version").asLong(),"notes"," "),true),400);
        admin.read(admin.call("POST",path,Map.of("version",0,"notes","Recipient"),true),409);
        d=admin.read(admin.call("POST",path,Map.of("version",o.path("version").asLong(),"notes","Received by customer"),true),200); assertFalse(d.path("order").path("deliveredAt").isNull());
        admin.read(admin.call("POST",path,Map.of("version",d.path("order").path("version").asLong(),"notes","Repeat"),true),409);
        assertEquals(3,d.path("events").size()); assertEquals("Received by customer",d.path("order").path("deliveryNotes").asText());
    }
    @Test void failedTrialNeedsDocumentedResolutionBeforeNewTrialAndRetainsOriginalResults() throws Exception {
        var f=operationsFixture(); var t=startTrial(f.order()).path("trials").get(0); var d=finishTrial(t,false);
        assertEquals("REWORK",d.path("order").path("status").asText()); assertTrue(d.path("order").path("reworkRequired").asBoolean()); assertTrue(d.path("order").path("completedAt").isNull());
        admin.read(admin.call("POST","/operations/orders/"+f.order()+"/start",startInput(d),true),409);
        t=d.path("trials").get(0); String path="/operations/trials/"+t.path("id").asText()+"/resolve";
        admin.read(admin.call("POST",path,Map.of("version",t.path("version").asLong(),"orderVersion",d.path("order").path("version").asLong(),"notes"," "),true),400);
        d=admin.read(admin.call("POST",path,Map.of("version",t.path("version").asLong(),"orderVersion",d.path("order").path("version").asLong(),"notes","Adjusted operating settings"),true),200);
        assertEquals("AWAITING_TESTING",d.path("order").path("status").asText()); assertFalse(d.path("trials").get(0).path("resolvedAt").isNull());
        var again=startTrial(f.order()).path("trials").get(0); assertNotEquals(t.path("id"),again.path("id")); d=finishTrial(again,true);
        assertEquals(2,d.path("trials").size()); assertEquals("FAILED",d.path("trials").get(1).path("status").asText()); assertEquals("FAIL",d.path("trials").get(1).path("checks").get(0).path("result").asText()); assertEquals("COMPLETED",d.path("order").path("status").asText());
    }
    @Test void trialRejectsIncompleteDuplicateForeignAndContradictoryCheckResultsAtomically() throws Exception {
        var f=operationsFixture(); var t=startTrial(f.order()).path("trials").get(0); String path="/operations/trials/"+t.path("id").asText()+"/finish";
        var p=finishInput(t,false); p.put("outcome","PASSED"); admin.read(admin.call("POST",path,p,true),409);
        p=finishInput(t,true); p.put("checks",List.of(Map.of("id",t.path("checks").get(0).path("id").asText(),"result","PASS","notes",""))); admin.read(admin.call("POST",path,p,true),409);
        p.put("checks",List.of(Map.of("id",t.path("checks").get(0).path("id").asText(),"result","PASS","notes",""),Map.of("id",t.path("checks").get(0).path("id").asText(),"result","PASS","notes",""))); admin.read(admin.call("POST",path,p,true),409);
        p.put("checks",List.of(Map.of("id",UUID.randomUUID().toString(),"result","PASS","notes",""),Map.of("id",t.path("checks").get(1).path("id").asText(),"result","PASS","notes",""))); admin.read(admin.call("POST",path,p,true),409);
        p.put("outcome","FAILED"); p.put("checks",List.of(Map.of("id",t.path("checks").get(0).path("id").asText(),"result","FAIL","notes"," "),Map.of("id",t.path("checks").get(1).path("id").asText(),"result","PASS","notes",""))); admin.read(admin.call("POST",path,p,true),409);
        var d=operations(f.order()); assertEquals("RUNNING",d.path("trials").get(0).path("status").asText()); assertEquals("PENDING",d.path("trials").get(0).path("checks").get(0).path("result").asText()); assertEquals(1,d.path("events").size());
    }
    @Test void removalInvalidatesTrialAndReinstallationCannotReviveOldResult() throws Exception {
        var f=operationsFixture(); var t=startTrial(f.order()).path("trials").get(0); String task=f.tasks().getFirst();
        taskAction(f.worker(),task,"uninstall"); var d=operations(f.order()); assertEquals("REWORK",d.path("order").path("status").asText()); assertEquals("INVALIDATED",d.path("trials").get(0).path("status").asText());
        assertEquals(4,d.path("trials").get(0).path("configuration").size()); assertEquals(3,d.path("order").path("installedCount").asInt());
        admin.read(admin.call("POST","/operations/trials/"+t.path("id").asText()+"/resolve",Map.of("version",d.path("trials").get(0).path("version").asLong(),"orderVersion",d.path("order").path("version").asLong(),"notes","Not ready"),true),409);
        taskAction(f.worker(),task,"install"); d=operations(f.order()); assertEquals("REWORK",d.path("order").path("status").asText()); assertEquals(6,d.path("order").path("configurationVersion").asInt());
        admin.read(admin.call("POST","/operations/trials/"+t.path("id").asText()+"/finish",finishInput(t,true),true),409);
        admin.read(admin.call("POST","/operations/trials/"+t.path("id").asText()+"/resolve",Map.of("version",d.path("trials").get(0).path("version").asLong(),"orderVersion",d.path("order").path("version").asLong(),"notes","Reinstalled and inspected"),true),200);
        d=startTrial(f.order()); assertEquals(6,d.path("trials").get(0).path("configurationVersion").asInt()); assertEquals("INVALIDATED",d.path("trials").get(1).path("status").asText());
    }
    @Test void concurrentTrialStartAndFinishHaveOneWinnerAndAbortRequiresResolution() throws Exception {
        var f=operationsFixture(); String path="/operations/orders/"+f.order()+"/start"; var p=startInput(operations(f.order()));
        var a=CompletableFuture.supplyAsync(()->catalogStatus("POST",path,p)); var b=CompletableFuture.supplyAsync(()->catalogStatus("POST",path,p)); assertEquals(Set.of(200,409),Set.of(a.join(),b.join()));
        var t=operations(f.order()).path("trials").get(0); var d=admin.read(admin.call("POST","/operations/trials/"+t.path("id").asText()+"/abort",Map.of("version",t.path("version").asLong(),"notes","Power interrupted"),true),200);
        assertEquals("REWORK",d.path("order").path("status").asText()); assertTrue(d.path("order").path("testedAt").isNull());
        t=d.path("trials").get(0); admin.read(admin.call("POST","/operations/trials/"+t.path("id").asText()+"/resolve",Map.of("version",t.path("version").asLong(),"orderVersion",d.path("order").path("version").asLong(),"notes","Power restored"),true),200);
        t=startTrial(f.order()).path("trials").get(0); String finish="/operations/trials/"+t.path("id").asText()+"/finish"; var body=finishInput(t,true);
        a=CompletableFuture.supplyAsync(()->catalogStatus("POST",finish,body)); b=CompletableFuture.supplyAsync(()->catalogStatus("POST",finish,body)); assertEquals(Set.of(200,409),Set.of(a.join(),b.join()));
        assertEquals(2L,db.sql("SELECT count(*) FROM operations_trials").query(Long.class).single()); assertEquals(1L,db.sql("SELECT count(*) FROM operations_events WHERE action='PASSED'").query(Long.class).single());
    }
    @Test void operationsOnlyPermissionsAreIndependentAndRevocationIsLive() throws Exception {
        var f=operationsFixture(); var ui=input("operations@test.local",List.of(),Map.of("operations.view",true,"operations.start",true));
        String id=admin.read(admin.call("POST","/users",ui,true),201).path("id").asText(); db.sql("UPDATE users SET must_change_password=false WHERE id=:id").param("id",UUID.fromString(id)).update();
        var operator=new Client(); operator.login("operations@test.local",INITIAL); operator.read(operator.call("GET","/operations/orders",null,false),200);
        for(String denied:List.of("/manufacturing-orders","/warehouse/pieces","/machines","/assembly/tasks")) operator.read(operator.call("GET",denied,null,false),403);
        var d=operator.read(operator.call("POST","/operations/orders/"+f.order()+"/start",startInput(operations(f.order())),true),200); var t=d.path("trials").get(0);
        operator.read(operator.call("POST","/operations/trials/"+t.path("id").asText()+"/finish",finishInput(t,true),true),403);
        operator.read(operator.call("POST","/operations/trials/"+t.path("id").asText()+"/resolve",Map.of("version",0,"orderVersion",0,"notes","No permission"),true),403);
        operator.read(operator.call("POST","/operations/orders/"+f.order()+"/deliver",Map.of("version",0,"notes","No permission"),true),403);
        ui.put("overrides",Map.of("operations.view",false,"operations.start",true)); admin.read(admin.call("PUT","/users/"+id,ui,true),200);
        operator.read(operator.call("GET","/operations/orders/"+f.order(),null,false),403); operator.read(operator.call("POST","/operations/orders/"+f.order()+"/start",startInput(d),true),403);
        f.worker().client().read(f.worker().client().call("GET","/operations/orders",null,false),403);
    }
    @Test void changesFromAnotherOrderInvalidateMachineTrialAndCompletedMachineLocksUntilDelivery() throws Exception {
        var f=operationsFixture(); var first=startTrial(f.order()).path("trials").get(0);
        String part=db.sql("SELECT part_id FROM order_requirements WHERE order_id=:id LIMIT 1").param("id",UUID.fromString(f.order())).query(UUID.class).single().toString();
        String bom=admin.read(admin.call("POST","/machines/"+f.machine()+"/boms",Map.of(),true),201).path("id").asText();
        var draft=admin.read(admin.call("GET","/boms/"+bom,null,false),200); long version=draft.path("version").asLong();
        for(var item:draft.path("items")) admin.read(admin.call("DELETE","/boms/"+bom+"/items/"+item.path("id").asText(),Map.of("version",version++),true),200);
        admin.read(admin.call("POST","/boms/"+bom+"/items",itemInput("B1",part,version),true),201); admin.read(admin.call("POST","/boms/"+bom+"/publish",Map.of("version",version+1),true),200);
        String owner=admin.read(admin.call("GET","/auth/me",null,false),200).path("id").asText();
        var otherFixture=new OrderFixture("",f.machine(),bom,part,owner); receipt(part,"OTHER1","OTHER2"); String other=openStockOrder(otherFixture,"other-machine-order").path("id").asText();
        List<String> pieces=new ArrayList<>(); for(var p:stock(other).path("pieces")) pieces.add(p.path("id").asText());
        for(var t:assemblyTasks(other)) assignTask(t,f.worker().id()); issueTo(other,f.worker().id(),pieces);
        for(var t:assemblyTasks(other)) { taskAction(f.worker(),t.path("id").asText(),"start"); taskAction(f.worker(),t.path("id").asText(),"install"); }
        var d=operations(f.order()); assertEquals("INVALIDATED",d.path("trials").get(0).path("status").asText()); assertEquals(4,d.path("trials").get(0).path("configuration").size());
        admin.read(admin.call("POST","/operations/trials/"+first.path("id").asText()+"/resolve",Map.of("version",d.path("trials").get(0).path("version").asLong(),"orderVersion",d.path("order").path("version").asLong(),"notes","Additional installation inspected"),true),200);
        var trial=startTrial(f.order()).path("trials").get(0); assertEquals(6,trial.path("configuration").size());
        assertEquals("OPERATIONS_ALREADY_RUNNING",admin.read(admin.call("POST","/operations/orders/"+other+"/start",startInput(operations(other)),true),409).path("code").asText());
        d=finishTrial(trial,true); var task=assemblyTasks(other).get(0); String path="/assembly/tasks/"+task.path("id").asText()+"/uninstall";
        var p=Map.of("version",task.path("version").asLong(),"reason","Must preserve tested configuration");
        assertEquals("OPERATIONS_COMPLETED_CONFIGURATION_LOCKED",f.worker().client().read(f.worker().client().call("POST",path,p,true),409).path("code").asText());
        assertEquals(6,operations(f.order()).path("order").path("configurationVersion").asInt()); assertEquals("INSTALLED",assemblyTask(task.path("id").asText()).path("status").asText());
        admin.read(admin.call("POST","/operations/orders/"+f.order()+"/deliver",Map.of("version",d.path("order").path("version").asLong(),"notes","Customer handover"),true),200);
        taskAction(f.worker(),task.path("id").asText(),"uninstall"); assertEquals("COMPLETED",operations(f.order()).path("order").path("status").asText());
    }
    @Test void openDefectBlocksTrialEvenAfterAllReplacementPiecesAreInstalled() throws Exception {
        var f=operationsFixture(); String task=f.tasks().getFirst(),piece=assemblyTask(task).path("pieceId").asText();
        taskAction(f.worker(),task,"uninstall"); var defect=defectAction(reject(new DefectFixture(f.order(),task,piece,f.worker())),"receive");
        String part=db.sql("SELECT part_id FROM stock_pieces WHERE id=:id").param("id",UUID.fromString(piece)).query(UUID.class).single().toString(); receipt(part,"REPLACEMENT");
        admin.read(admin.call("POST","/manufacturing-orders/"+f.order()+"/stock/reserve",Map.of("version",stock(f.order()).path("version").asLong()),true),200);
        String replacement=""; for(var p:stock(f.order()).path("pieces")) if(p.path("status").asText().equals("RESERVED")) replacement=p.path("id").asText();
        issueTo(f.order(),f.worker().id(),List.of(replacement)); taskAction(f.worker(),task,"start"); taskAction(f.worker(),task,"install");
        assertEquals("OPERATIONS_OPEN_DEFECTS",admin.read(admin.call("POST","/operations/orders/"+f.order()+"/start",startInput(operations(f.order())),true),409).path("code").asText());
        defectAction(defect,"scrap"); startTrial(f.order());
    }
    @Test void unreturnedRawCustodyBlocksTrialAfterExternalStockFulfillsPreparation() throws Exception {
        var f=orderFixture(); String oid=openStockOrder(f,"raw-trial").path("id").asText(); var q=preparationFor(oid);
        String raw=rawPart("trial-bar","LENGTH").path("id").asText(); var unit=rawIssue(rawReceive(raw,"TRIAL-RAW",500,60),q);
        receipt(f.part(),"EX1","EX2","EX3","EX4"); admin.read(admin.call("POST","/manufacturing-orders/"+oid+"/stock/reserve",Map.of("version",stock(oid).path("version").asLong()),true),200);
        var worker=assemblyWorker("raw-trial-worker"); for(var t:assemblyTasks(oid)) assignTask(t,worker.id()); List<String> pieces=new ArrayList<>(); for(var p:stock(oid).path("pieces")) pieces.add(p.path("id").asText()); issueTo(oid,worker.id(),pieces);
        for(var t:assemblyTasks(oid)) { taskAction(worker,t.path("id").asText(),"start"); taskAction(worker,t.path("id").asText(),"install"); }
        assertEquals("OPERATIONS_OPEN_CUSTODY",admin.read(admin.call("POST","/operations/orders/"+oid+"/start",startInput(operations(oid)),true),409).path("code").asText());
        admin.read(admin.call("POST","/warehouse/raw/units/"+unit.path("id").asText()+"/return",Map.of("version",unit.path("version").asLong(),"notes","Unused raw source returned"),true),200); startTrial(oid);
    }
    JsonNode workDetail(String task) throws Exception { return admin.read(admin.call("GET","/assembly/tasks/"+task,null,false),200).path("workTime"); }
    void clockAt(String instant) { when(workClock.instant()).thenReturn(Instant.parse(instant)); }
    void approveBreaks() throws Exception { admin.read(admin.call("PUT","/shifts",Map.of("version",0,"weekdays",List.of(1,2,3,4,6,7),"breaks",List.of(Map.of("start","12:00","end","12:30")),"breaksConfirmed",true),true),200); }
    @Test void calendarDefaultsFridayOffAndRequiresApprovalAndOptimisticVersion() throws Exception {
        var c=admin.read(admin.call("GET","/shifts",null,false),200); assertFalse(c.path("breaksConfirmed").asBoolean()); assertEquals(6,c.path("weekdays").size());
        var day=admin.read(admin.call("GET","/shifts/days?from=2026-10-16&to=2026-10-16",null,false),200).get(0); assertFalse(day.path("working").asBoolean());
        approveBreaks(); admin.read(admin.call("PUT","/shifts",Map.of("version",0,"weekdays",List.of(6),"breaks",List.of(),"breaksConfirmed",true),true),409);
        admin.read(admin.call("PUT","/shifts/days/2026-10-16",Map.of("version",0,"working",true,"extended",true,"reason","Friday production"),true),200);
        admin.read(admin.call("PUT","/shifts/days/2026-10-11",Map.of("version",0,"working",true,"extended",false,"reason","Past"),true),409);
        admin.read(admin.call("GET","/shifts/days?from=2026-01-01&to=2026-12-31",null,false),409);
    }
    @Test void fridayRequiresDatedApprovalAndCannotBeMadeRecurring() throws Exception {
        var rejected=admin.read(admin.call("PUT","/shifts",Map.of("version",0,"weekdays",List.of(1,2,3,4,5,6,7),"breaks",List.of(),"breaksConfirmed",true),true),409);
        assertEquals("SHIFT_INVALID",rejected.path("code").asText());
        assertEquals(0,admin.read(admin.call("GET","/shifts",null,false),200).path("version").asLong());
        admin.read(admin.call("PUT","/shifts/days/2026-10-16",Map.of("version",0,"working",true,"extended",false,"reason",""),true),400);
        var approved=admin.read(admin.call("PUT","/shifts/days/2026-10-16",Map.of("version",0,"working",true,"extended",false,"reason","Manager approved dated production"),true),200);
        assertTrue(approved.path("working").asBoolean()); assertFalse(approved.path("extended").asBoolean());
        assertFalse(admin.read(admin.call("GET","/shifts/days?from=2026-10-23&to=2026-10-23",null,false),200).get(0).path("working").asBoolean());
    }
    @Test void timerPausesResumesDeductsApprovedBreakAndStopsOnInstall() throws Exception {
        approveBreaks(); var f=defectFixture("timer"); clockAt("2026-10-12T08:45:00Z"); taskAction(f.worker(),f.task(),"start");
        clockAt("2026-10-12T09:15:00Z"); taskAction(f.worker(),f.task(),"pause");
        assertEquals(1800,workDetail(f.task()).path("total").path("grossSeconds").asLong()); assertEquals(900,workDetail(f.task()).path("total").path("netSeconds").asLong());
        clockAt("2026-10-12T09:30:00Z"); taskAction(f.worker(),f.task(),"resume"); clockAt("2026-10-12T10:00:00Z"); taskAction(f.worker(),f.task(),"install");
        var d=workDetail(f.task()); assertEquals(2,d.path("sessions").size()); assertEquals(2700,d.path("total").path("netSeconds").asLong()); assertEquals(0L,db.sql("SELECT count(*) FROM work_sessions WHERE ended_at IS NULL").query(Long.class).single());
    }
    @Test void unapprovedBreaksRemainUnknownAndExpiredTimerCannotGainRetroactiveExtension() throws Exception {
        var f=defectFixture("late-extend"); clockAt("2026-10-12T13:00:00Z"); taskAction(f.worker(),f.task(),"start"); clockAt("2026-10-12T15:00:00Z");
        admin.read(admin.call("PUT","/shifts/days/2026-10-12",Map.of("version",0,"working",true,"extended",true,"reason","Extra production"),true),200);
        var d=workDetail(f.task()); assertEquals(3600,d.path("total").path("grossSeconds").asLong()); assertTrue(d.path("total").path("netSeconds").isNull()); assertFalse(d.path("sessions").get(0).path("endedAt").isNull());
        taskAction(f.worker(),f.task(),"resume"); clockAt("2026-10-12T18:00:00Z"); taskAction(f.worker(),f.task(),"install"); assertEquals(3*3600,workDetail(f.task()).path("total").path("grossSeconds").asLong());
    }
    @Test void fridayCanOpenAfterZeroDurationOutsideShiftAttempt() throws Exception {
        var f=defectFixture("friday"); clockAt("2026-10-16T09:00:00Z"); taskAction(f.worker(),f.task(),"start"); assertEquals(0,workDetail(f.task()).path("total").path("grossSeconds").asLong());
        f.worker().client().read(f.worker().client().call("POST","/assembly/tasks/"+f.task()+"/resume",Map.of("version",assemblyTask(f.task()).path("version").asLong(),"notes","Friday"),true),409);
        admin.read(admin.call("PUT","/shifts/days/2026-10-16",Map.of("version",0,"working",true,"extended",false,"reason","Friday approved"),true),200); taskAction(f.worker(),f.task(),"resume");
        clockAt("2026-10-16T10:00:00Z"); taskAction(f.worker(),f.task(),"install"); assertEquals(3600,workDetail(f.task()).path("total").path("grossSeconds").asLong());
    }
    @Test void oneActiveTimerPerWorkerAndOtherWorkerCannotControlIt() throws Exception {
        var f=defectFixture("single-timer"); var other=assemblyWorker("other-timer"); var task=assemblyTasks(f.order()).get(1); assignTask(task,f.worker().id()); String second=task.path("id").asText();
        String part=assemblyTask(f.task()).path("partId").asText(); receipt(part,"TIMER-SECOND"); admin.read(admin.call("POST","/manufacturing-orders/"+f.order()+"/stock/reserve",Map.of("version",stock(f.order()).path("version").asLong()),true),200);
        String piece=""; for(var p:stock(f.order()).path("pieces")) if(p.path("status").asText().equals("RESERVED")) piece=p.path("id").asText(); issueTo(f.order(),f.worker().id(),List.of(piece));
        taskAction(f.worker(),f.task(),"start"); f.worker().client().read(f.worker().client().call("POST","/assembly/tasks/"+second+"/start",Map.of("version",assemblyTask(second).path("version").asLong(),"notes","Second"),true),409);
        other.client().read(other.client().call("POST","/assembly/tasks/"+f.task()+"/pause",Map.of("version",assemblyTask(f.task()).path("version").asLong(),"notes","Not mine"),true),404);
        taskAction(f.worker(),f.task(),"pause"); taskAction(f.worker(),second,"start"); assertEquals(1L,db.sql("SELECT count(*) FROM work_sessions WHERE ended_at IS NULL").query(Long.class).single());
    }
    HttpResponse<String> upload(Client client,String target,String content) throws Exception {
        String boundary="easyback-integration-boundary"; String body="--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"drawing.pdf\"\r\nContent-Type: application/pdf\r\n\r\n"+content+"\r\n--"+boundary+"--\r\n";
        var r=HttpRequest.newBuilder(client.uri("/files?kind=ORDER&targetId="+target)).header("Authorization","Bearer "+client.access).header("X-XSRF-TOKEN",client.csrf).header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build();
        return client.http.send(r,HttpResponse.BodyHandlers.ofString());
    }
    @Test void attachmentsValidateBytesScopeDuplicatesAndVersionedDeletion() throws Exception {
        var f=defectFixture("files"); String pdf="%PDF-1.4\n1 0 obj << /Type /Catalog >> endobj\n%%EOF";
        var file=admin.read(upload(admin,f.order(),pdf),201); String id=file.path("id").asText(); admin.read(upload(admin,f.order(),pdf),409); admin.read(upload(admin,f.order(),"<html>not a PDF</html>"),400);
        var w=f.worker().client(); assertEquals(pdf,w.call("GET","/files/"+id,null,false).body()); assertEquals(200,w.call("GET","/files/"+id,null,false).statusCode()); w.read(upload(w,f.order(),pdf+"\n"),403);
        var outsider=assemblyWorker("no-files"); outsider.client().read(outsider.client().call("GET","/files/"+id,null,false),404);
        admin.read(admin.call("DELETE","/files/"+id,Map.of("version",1),true),409); admin.read(admin.call("DELETE","/files/"+id,Map.of("version",0),true),204); w.read(w.call("GET","/files/"+id,null,false),404);
    }
    @Test void templatesAreVersionedAndTrialsKeepCopiedCriteria() throws Exception {
        var f=operationsFixture(); var p=new HashMap<String,Object>(Map.of("version",0,"name","Factory checklist","active",true,"checks",List.of(Map.of("label","Safety","criterion","Guard verified"))));
        var template=admin.read(admin.call("POST","/operations/templates",p,true),201); var start=startInput(operations(f.order())); start.put("checks",template.path("checks")); var trial=admin.read(admin.call("POST","/operations/orders/"+f.order()+"/start",start,true),200).path("trials").get(0);
        p.put("checks",List.of(Map.of("label","Changed","criterion","New standard"))); admin.read(admin.call("PUT","/operations/templates/"+template.path("id").asText(),p,true),200); admin.read(admin.call("PUT","/operations/templates/"+template.path("id").asText(),p,true),409);
        assertEquals("Safety",operations(f.order()).path("trials").get(0).path("checks").get(0).path("label").asText()); assertEquals("RUNNING",trial.path("status").asText()); f.worker().client().read(f.worker().client().call("GET","/operations/templates",null,false),403);
    }
    @Test void reportsArePermissionedBoundedAndCsvEscapesFormulas() throws Exception {
        var f=defectFixture("reports"); db.sql("UPDATE manufacturing_orders SET title='=HYPERLINK(\"bad\")' WHERE id=:id").param("id",UUID.fromString(f.order())).update();
        taskAction(f.worker(),f.task(),"start"); db.sql("DELETE FROM work_sessions WHERE task_id=:id").param("id",UUID.fromString(f.task())).update();
        String range="?from=2026-01-01&to=2026-12-31"; var report=admin.read(admin.call("GET","/reports"+range,null,false),200); assertEquals(1,report.path("total").asInt()); assertEquals(3,report.path("missing").asInt());
        assertEquals(1,report.path("orders").get(0).path("untrackedTasks").asInt()); assertTrue(report.path("orders").get(0).path("time").path("netSeconds").isNull());
        var csv=admin.call("GET","/reports/export"+range,null,false); assertEquals(200,csv.statusCode()); assertTrue(csv.body().contains("'=HYPERLINK")); assertTrue(csv.headers().firstValue("Content-Type").orElseThrow().contains("text/csv"));
        admin.read(admin.call("GET","/reports?from=2025-01-01&to=2026-12-31",null,false),400); f.worker().client().read(f.worker().client().call("GET","/reports"+range,null,false),403);
    }
    void loseLegacySessions(DefectFixture f) {
        db.sql("DELETE FROM work_sessions WHERE task_id=:id").param("id",UUID.fromString(f.task())).update();
    }
    void assertIncompleteReport(DefectFixture f) throws Exception {
        var report=admin.read(admin.call("GET","/reports?from=2026-01-01&to=2026-12-31",null,false),200).path("orders").get(0);
        assertEquals(1,report.path("untrackedTasks").asInt()); assertTrue(report.path("time").path("netSeconds").isNull());
        assertTrue(workDetail(f.task()).path("total").path("netSeconds").isNull());
    }
    @Test void legacyResumeKeepsUnknownNetEvenAfterTrackedWork() throws Exception {
        approveBreaks(); var f=defectFixture("legacy-resume"); taskAction(f.worker(),f.task(),"start"); loseLegacySessions(f);
        taskAction(f.worker(),f.task(),"resume"); clockAt("2026-10-12T10:00:00Z"); taskAction(f.worker(),f.task(),"pause");
        assertEquals(3600,workDetail(f.task()).path("total").path("grossSeconds").asLong()); assertIncompleteReport(f);
    }
    @Test void v12BackfillsLegacyEventEvidenceEvenAfterStartWasClearedAndTrackingResumed() throws Exception {
        var f=defectFixture("legacy-backfill"); taskAction(f.worker(),f.task(),"start");
        UUID task=UUID.fromString(f.task());
        db.sql("UPDATE assembly_tasks SET status='PENDING',started_at=NULL WHERE id=:id").param("id",task).update();
        db.sql("UPDATE assembly_events SET created_at=(SELECT installed_on AT TIME ZONE current_setting('TimeZone') - interval '1 day' FROM flyway_schema_history WHERE version='10' AND success) WHERE task_id=:id AND action='STARTED'").param("id",task).update();
        String migration=new org.springframework.core.io.ClassPathResource("db/migration/V12__incomplete_time_history.sql").getContentAsString(StandardCharsets.UTF_8);
        db.sql(migration.substring(migration.indexOf("WITH tracking"))).update();
        assertIncompleteReport(f);
        assertEquals(1L,db.sql("SELECT count(*) FROM work_sessions WHERE task_id=:id").param("id",task).query(Long.class).single());
    }
    @Test void legacyReturnAndReassignmentKeepUnknownNetAfterStartIsCleared() throws Exception {
        approveBreaks(); var f=defectFixture("legacy-return"); taskAction(f.worker(),f.task(),"start"); loseLegacySessions(f);
        admin.read(admin.call("POST","/manufacturing-orders/"+f.order()+"/stock/pieces/"+f.piece()+"/return",Map.of("version",stock(f.order()).path("version").asLong(),"condition","GOOD","notes","Return legacy piece"),true),200);
        assertTrue(assemblyTask(f.task()).path("startedAt").isNull()); assertIncompleteReport(f);
        var other=assemblyWorker("legacy-reassigned"); assignTask(assemblyTask(f.task()),other.id()); assertIncompleteReport(f);
    }
    @Test void legacyReworkRetainsWarningButFullyTrackedReworkDoesNotAddOne() throws Exception {
        approveBreaks(); var f=defectFixture("legacy-rework"); taskAction(f.worker(),f.task(),"start"); clockAt("2026-10-12T10:00:00Z"); taskAction(f.worker(),f.task(),"install");
        taskAction(f.worker(),f.task(),"uninstall"); taskAction(f.worker(),f.task(),"install");
        var tracked=admin.read(admin.call("GET","/reports?from=2026-01-01&to=2026-12-31",null,false),200).path("orders").get(0);
        assertEquals(0,tracked.path("untrackedTasks").asInt()); assertFalse(tracked.path("time").path("netSeconds").isNull());
        loseLegacySessions(f); taskAction(f.worker(),f.task(),"uninstall"); taskAction(f.worker(),f.task(),"resume"); taskAction(f.worker(),f.task(),"install"); assertIncompleteReport(f);
    }
    @Test void reportJsonAndCsvShareMixedDataFiltersAndZeroMatches() throws Exception {
        var fixture=orderFixture(); String order=openStockOrder(fixture,"filter-report").path("id").asText();
        String other=newOrder(orderInput("other-report",fixture)).path("id").asText();
        db.sql("UPDATE manufacturing_orders SET title='Pump, \"Assembly\"',delivery_at='2026-10-01T09:00:00Z' WHERE id=:id").param("id",UUID.fromString(order)).update();
        db.sql("UPDATE manufacturing_orders SET title='Other report',delivery_at='2026-12-01T09:00:00Z' WHERE id=:id").param("id",UUID.fromString(other)).update();
        String range="?from=2026-01-01&to=2026-12-31";
        for(String filters:List.of("&q=pUmP&status=OPEN&late=true&missing=true","&status=OPEN&late=true","&q=no-such-order","&status=COMPLETED","&q=Pump&late=false&missing=true")) {
            var report=admin.read(admin.call("GET","/reports"+range+filters,null,false),200);
            var csv=admin.call("GET","/reports/export"+range+filters,null,false); assertEquals(200,csv.statusCode());
            assertEquals(report.path("total").asLong()+1,csv.body().lines().count());
            assertFalse(csv.body().contains("Other report"));
            if(report.path("total").asInt()>0) { assertEquals(order,report.path("orders").get(0).path("id").asText()); assertTrue(csv.body().contains("\"Pump, \"\"Assembly\"\"\"")); }
        }
        var viewer=input("report-viewer@test.local",List.of(),Map.of("reports.view",true));
        admin.read(admin.call("POST","/users",viewer,true),201); db.sql("UPDATE users SET must_change_password=false WHERE email='report-viewer@test.local'").update();
        var viewOnly=new Client(); viewOnly.login("report-viewer@test.local",INITIAL);
        viewOnly.read(viewOnly.call("GET","/reports"+range+"&q=Pump",null,false),200);
        viewOnly.read(viewOnly.call("GET","/reports/export"+range+"&q=Pump",null,false),403);
    }
    JsonNode inbox(Client c) throws Exception { return c.read(c.call("GET","/notifications?limit=50",null,false),200).path("items"); }
    long unread(Client c) throws Exception { return c.read(c.call("GET","/notifications/unread-count",null,false),200).path("count").asLong(); }
    long eventCount(String type) { return db.sql("SELECT count(*) FROM notifications WHERE type=:type").param("type",type).query(Long.class).single(); }
    Worker notificationUser(String name,String... permissions) throws Exception {
        Map<String,Boolean> grants=new HashMap<>(); for(String p:permissions) grants.put(p,true);
        var u=admin.read(admin.call("POST","/users",input(name+"@test.local",List.of(),grants),true),201);
        db.sql("UPDATE users SET must_change_password=false WHERE id=:id").param("id",UUID.fromString(u.path("id").asText())).update();
        var c=new Client(); c.login(name+"@test.local",INITIAL); return new Worker(u.path("id").asText(),c);
    }
    void override(String user,String permission,boolean allowed) {
        db.sql("INSERT INTO user_permission_overrides(user_id,permission,allowed) VALUES (:id,:permission,:allowed) ON CONFLICT(user_id,permission) DO UPDATE SET allowed=EXCLUDED.allowed")
            .param("id",UUID.fromString(user)).param("permission",permission).param("allowed",allowed).update();
    }
    @Test void notificationAssignmentsArePrivateIdempotentAndNeverReviveSupersededOccurrences() throws Exception {
        var f=orderFixture(); String order=openStockOrder(f,"notification-assign").path("id").asText();
        var a=assemblyWorker("notification-a"); var b=assemblyWorker("notification-b");
        var t=assignTask(assemblyTasks(order).get(0),a.id()); String id=inbox(a.client()).get(0).path("id").asText();
        assertEquals(1,unread(a.client())); assertEquals(0,unread(b.client()));
        assertEquals("/tasks?task="+t.path("id").asText(),inbox(a.client()).get(0).path("target").asText());
        b.client().read(b.client().call("POST","/notifications/"+id+"/read",null,true),404);
        var cookieOnly=new Client(); cookieOnly.read(cookieOnly.call("POST","/notifications/"+id+"/read",null,false),403);
        // Existing resource-server policy exempts explicit Bearer requests from CSRF.
        a.client().read(a.client().call("POST","/notifications/"+id+"/read",null,false),204);
        a.client().read(a.client().call("POST","/notifications/"+id+"/read",null,true),204);
        String readAt=inbox(a.client()).get(0).path("readAt").asText();
        when(workClock.instant()).thenReturn(Instant.parse("2026-10-12T10:00:00Z"));
        a.client().read(a.client().call("POST","/notifications/"+id+"/read",null,true),204);
        assertEquals(readAt,inbox(a.client()).get(0).path("readAt").asText());
        t=assignTask(t,a.id()); assertEquals(1,eventCount("TASK_ASSIGNED"));
        t=assignTask(t,b.id()); assertEquals(0,inbox(a.client()).size());
        a.client().read(a.client().call("POST","/notifications/"+id+"/read",null,true),404);
        t=assignTask(t,a.id()); assertEquals(1,inbox(a.client()).size()); assertNotEquals(id,inbox(a.client()).get(0).path("id").asText());
        override(a.id(),"tasks.view-assigned",false); assertEquals(0,unread(a.client())); assertEquals(0,inbox(a.client()).size());
        override(a.id(),"tasks.view-assigned",true); assertEquals(1,unread(a.client()));
        a.client().read(a.client().call("POST","/notifications/read-all",null,true),204); assertEquals(0,unread(a.client()));
        db.sql("UPDATE users SET active=false WHERE id=:id").param("id",UUID.fromString(a.id())).update();
        a.client().read(a.client().call("GET","/notifications",null,false),401);
    }
    @Test void notificationPaginationUsesStableTiesAndValidatesInputs() throws Exception {
        var f=orderFixture(); String order=openStockOrder(f,"notification-pages").path("id").asText(); var w=assemblyWorker("notification-pages");
        for(var task:assemblyTasks(order)) assignTask(task,w.id());
        Set<String> seen=new HashSet<>(); String cursor=null;
        do {
            var page=w.client().read(w.client().call("GET","/notifications?limit=1"+(cursor==null?"":"&cursor="+cursor),null,false),200);
            assertEquals(1,page.path("items").size()); assertTrue(seen.add(page.path("items").get(0).path("id").asText()));
            cursor=page.path("nextCursor").isNull()?null:page.path("nextCursor").asText();
        } while(cursor!=null);
        assertEquals(4,seen.size());
        for(String q:List.of("limit=0","limit=51","severity=BAD","cursor=invalid","unreadOnly=garbage")) w.client().read(w.client().call("GET","/notifications?"+q,null,false),400);
        w.client().read(w.client().call("POST","/notifications/read-all",null,true),204);
        assertEquals(0,w.client().read(w.client().call("GET","/notifications?unreadOnly=true",null,false),200).path("items").size());
        assertEquals(4,inbox(w.client()).size());
    }
    @Test void notificationWritesRollbackWithBusinessActionAndDeduplicateAtDatabase() throws Exception {
        var f=orderFixture(); String order=openStockOrder(f,"notification-rollback").path("id").asText(); var w=assemblyWorker("notification-rollback"); var task=assemblyTasks(order).get(0);
        UUID taskId=UUID.fromString(task.path("id").asText()),actor=UUID.fromString(f.owner());
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(s->{
            assemblyService.assign(taskId,new com.easyback.assembly.AssemblyData.Assignment(task.path("version").asLong(),UUID.fromString(w.id()),"Rollback test"),actor);
            throw new IllegalStateException("simulate later business failure");
        }));
        assertEquals(0,eventCount("TASK_ASSIGNED")); assertTrue(assemblyTask(taskId.toString()).path("workerId").isNull());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->tx.executeWithoutResult(s->{
            db.sql("ALTER TABLE notification_recipients ADD CONSTRAINT notification_test_failure CHECK(false) NOT VALID").update();
            assemblyService.assign(taskId,new com.easyback.assembly.AssemblyData.Assignment(task.path("version").asLong(),UUID.fromString(w.id()),"Writer failure test"),actor);
        }));
        assertEquals(0,eventCount("TASK_ASSIGNED")); assertTrue(assemblyTask(taskId.toString()).path("workerId").isNull());
        assignTask(task,w.id());
        tx.executeWithoutResult(s->notificationWriter.emit(com.easyback.notifications.NotificationWriter.Kind.TASK_ASSIGNED,UUID.fromString(order),taskId,"1",actor));
        assertEquals(1,eventCount("TASK_ASSIGNED")); assertEquals(1,inbox(w.client()).size());
    }
    @Test void notificationRecipientsReadIndependentlyAndHiddenNewestDoesNotConsumePage() throws Exception {
        var f=orderFixture(); UUID order=UUID.fromString(openStockOrder(f,"notification-shared").path("id").asText());
        assertEquals(2,db.sql("SELECT count(*) FROM notifications").query(Long.class).single());
        assertEquals(0,db.sql("SELECT count(*) FROM notification_recipients").query(Long.class).single(),"Actor suppression retains events even without another eligible user");
        var a=notificationUser("notification-shared-a","orders.view","stock.view","stock.reserve","operations.view","operations.start");
        var b=notificationUser("notification-shared-b","orders.view","stock.view","stock.reserve");
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager); UUID actor=UUID.fromString(f.owner());
        tx.executeWithoutResult(s->notificationWriter.emit(com.easyback.notifications.NotificationWriter.Kind.ORDER_SHORTAGE,order,null,"shared",actor));
        String id=inbox(a.client()).get(0).path("id").asText(); assertEquals(id,inbox(b.client()).get(0).path("id").asText());
        a.client().read(a.client().call("POST","/notifications/"+id+"/read",null,true),204);
        assertEquals(0,unread(a.client())); assertEquals(1,unread(b.client()));
        when(workClock.instant()).thenReturn(Instant.parse("2026-10-12T10:00:00Z"));
        tx.executeWithoutResult(s->notificationWriter.emit(com.easyback.notifications.NotificationWriter.Kind.ORDER_READY_FOR_TEST,order,null,"hidden-newest",actor));
        override(a.id(),"operations.start",false);
        var page=a.client().read(a.client().call("GET","/notifications?limit=1",null,false),200);
        assertEquals(id,page.path("items").get(0).path("id").asText()); assertTrue(page.path("nextCursor").isNull());
        db.sql("UPDATE users SET active=false WHERE id=:id").param("id",UUID.fromString(b.id())).update();
        tx.executeWithoutResult(s->notificationWriter.emit(com.easyback.notifications.NotificationWriter.Kind.ORDER_SHORTAGE,order,null,"active-only",actor));
        assertEquals(1,db.sql("SELECT count(*) FROM notification_recipients r JOIN notifications n ON n.id=r.notification_id WHERE n.dedupe_key=:key")
            .param("key","ORDER_SHORTAGE:"+order+":active-only").query(Long.class).single());
    }
    @Test void notificationDefectHandoffsHonorAlternativeCapabilitiesAndFreezeSafeSummary() throws Exception {
        var receiver=notificationUser("notification-receiver","defects.view","orders.view","stock.view","stock.return","defects.manage","stock.receive");
        var missingPrerequisite=notificationUser("notification-no-order","defects.view","defects.manage");
        var f=defectFixture("notification-defect"); var d=reject(f);
        assertEquals(1,eventCount("DEFECT_REPORTED")); assertEquals(0,inbox(missingPrerequisite.client()).size());
        var item=inbox(receiver.client()).get(0); String id=item.path("id").asText(),body=item.path("body").asText();
        assertFalse(item.toString().contains("Cracked mounting face"));
        override(receiver.id(),"stock.return",false); assertEquals(1,unread(receiver.client()));
        assertEquals("/defects?case="+d.path("id").asText(),inbox(receiver.client()).get(0).path("target").asText());
        receiver.client().read(receiver.client().call("POST","/notifications/"+id+"/read",null,true),204);
        d=defectAction(d,"receive"); assertEquals(1,eventCount("DEFECT_RECEIVED"));
        d=defectAction(d,"repair-start"); d=defectAction(d,"repair-finish"); assertEquals(1,eventCount("REPAIR_COMPLETED"));
        db.sql("UPDATE manufacturing_orders SET title='changed title' WHERE id=:id").param("id",UUID.fromString(f.order())).update();
        assertTrue(inbox(receiver.client()).toString().contains(body)); assertFalse(inbox(receiver.client()).toString().contains("changed title"));
        override(receiver.id(),"orders.view",false); assertEquals(0,unread(receiver.client())); assertEquals(0,inbox(receiver.client()).size());
    }
    @Test void notificationOwnerFallsBackToOrderAfterOperationalAccessRevocation() throws Exception {
        var f=orderFixture(); var owner=notificationUser("notification-owner","orders.view-assigned","operations.view","operations.start");
        var input=orderInput("notification-owner",f); input.put("responsibleId",owner.id()); String order=newOrder(input).path("id").asText();
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        tx.executeWithoutResult(s->notificationWriter.emit(com.easyback.notifications.NotificationWriter.Kind.ORDER_READY_FOR_TEST,UUID.fromString(order),null,"test",UUID.fromString(f.owner())));
        String id=inbox(owner.client()).get(0).path("id").asText(); assertEquals("/operations/"+order,inbox(owner.client()).get(0).path("target").asText());
        override(owner.id(),"operations.start",false); override(owner.id(),"operations.view",false);
        assertEquals(1,unread(owner.client())); assertEquals("/orders/"+order,inbox(owner.client()).get(0).path("target").asText());
        owner.client().read(owner.client().call("POST","/notifications/"+id+"/read",null,true),204);
        override(owner.id(),"orders.view-assigned",false); assertEquals(0,inbox(owner.client()).size());
    }
    @Test void notificationPreparationTransitionsOnlyEmitOnceAndReopenOnActualShortage() throws Exception {
        var manager=notificationUser("notification-preparation","orders.view","preparation.manage","stock.view","stock.issue");
        var f=orderFixture(); String order=openStockOrder(f,"notification-preparation").path("id").asText();
        assertEquals(1,eventCount("ORDER_SHORTAGE")); assertEquals(0,eventCount("PREPARATION_REQUESTED"));
        var prep=preparationFor(order); assertEquals(1,eventCount("PREPARATION_REQUESTED"));
        receipt(f.part(),"NOTIFY1","NOTIFY2","NOTIFY3","NOTIFY4");
        admin.read(admin.call("POST","/manufacturing-orders/"+order+"/stock/reserve",Map.of("version",stock(order).path("version").asLong()),true),200);
        assertEquals(1,eventCount("PREPARATION_FULFILLED"));
        admin.read(admin.call("POST","/manufacturing-orders/"+order+"/stock/reserve",Map.of("version",stock(order).path("version").asLong()),true),200);
        assertEquals(1,eventCount("PREPARATION_FULFILLED"));
        String piece=stock(order).path("pieces").get(0).path("id").asText();
        admin.read(admin.call("POST","/manufacturing-orders/"+order+"/stock/issue",Map.of("version",stock(order).path("version").asLong(),"pieceIds",List.of(piece),"notes",""),true),200);
        admin.read(admin.call("POST","/manufacturing-orders/"+order+"/stock/pieces/"+piece+"/return",Map.of("version",stock(order).path("version").asLong(),"condition","GOOD","notes","Returned"),true),200);
        assertEquals(2,eventCount("PREPARATION_REQUESTED")); assertTrue(inbox(manager.client()).size()>=3);
        assertEquals("REQUESTED",rawPreparation(prep.path("id").asText()).path("status").asText());
    }
    @Test void notificationTrialCyclesCoverReadyFailureAbortInvalidationAndCompletion() throws Exception {
        var operator=notificationUser("notification-operator","operations.view","operations.start","operations.resolve","operations.deliver");
        var f=operationsFixture(); assertEquals(1,eventCount("ORDER_READY_FOR_TEST"));
        var t=startTrial(f.order()).path("trials").get(0); var detail=finishTrial(t,false); assertEquals(1,eventCount("TRIAL_FAILED"));
        t=detail.path("trials").get(0);
        admin.read(admin.call("POST","/operations/trials/"+t.path("id").asText()+"/resolve",Map.of("version",t.path("version").asLong(),"orderVersion",detail.path("order").path("version").asLong(),"notes","Fixed"),true),200);
        assertEquals(2,eventCount("ORDER_READY_FOR_TEST"));
        t=startTrial(f.order()).path("trials").get(0); detail=finishTrial(t,false); assertEquals(2,eventCount("TRIAL_FAILED"));
        t=detail.path("trials").get(0);
        admin.read(admin.call("POST","/operations/trials/"+t.path("id").asText()+"/resolve",Map.of("version",t.path("version").asLong(),"orderVersion",detail.path("order").path("version").asLong(),"notes","Second repair"),true),200);
        t=startTrial(f.order()).path("trials").get(0); detail=admin.read(admin.call("POST","/operations/trials/"+t.path("id").asText()+"/abort",Map.of("version",t.path("version").asLong(),"notes","Power outage"),true),200);
        assertEquals(1,eventCount("TRIAL_ABORTED")); t=detail.path("trials").get(0);
        admin.read(admin.call("POST","/operations/trials/"+t.path("id").asText()+"/resolve",Map.of("version",t.path("version").asLong(),"orderVersion",detail.path("order").path("version").asLong(),"notes","Power restored"),true),200);
        startTrial(f.order()); taskAction(f.worker(),f.tasks().getFirst(),"uninstall"); assertEquals(1,eventCount("TRIAL_INVALIDATED"));
        taskAction(f.worker(),f.tasks().getFirst(),"install"); detail=operations(f.order()); t=detail.path("trials").get(0);
        admin.read(admin.call("POST","/operations/trials/"+t.path("id").asText()+"/resolve",Map.of("version",t.path("version").asLong(),"orderVersion",detail.path("order").path("version").asLong(),"notes","Inspected"),true),200);
        finishTrial(startTrial(f.order()).path("trials").get(0),true); assertEquals(1,eventCount("ORDER_COMPLETED"));
        assertEquals(10,inbox(operator.client()).size());
    }
    @Test void notificationDeadlinesAreExclusiveDeduplicatedAndIncludeCompletedUndelivered() throws Exception {
        var f=orderFixture(); UUID order=UUID.fromString(openStockOrder(f,"notification-due").path("id").asText());
        Instant due=Instant.parse("2026-10-14T09:00:00Z");
        db.sql("UPDATE manufacturing_orders SET delivery_at=:due WHERE id=:id").param("id",order).param("due",java.sql.Timestamp.from(due)).update();
        when(workClock.instant()).thenReturn(due.minusSeconds(86401)); reminderEvaluator.evaluate(order); assertEquals(0,eventCount("DELIVERY_DUE_SOON"));
        when(workClock.instant()).thenReturn(due.minusSeconds(86400)); reminderEvaluator.evaluate(order); reminderEvaluator.evaluate(order); assertEquals(1,eventCount("DELIVERY_DUE_SOON"));
        when(workClock.instant()).thenReturn(due); reminderEvaluator.evaluate(order); reminderEvaluator.evaluate(order); assertEquals(1,eventCount("DELIVERY_OVERDUE"));
        UUID completed=UUID.fromString(openStockOrder(f,"notification-completed").path("id").asText());
        UUID passed=UUID.randomUUID();
        db.sql("INSERT INTO operations_trials(id,order_id,machine_id,configuration_version,status,started_by,start_notes,finished_by,finished_at,result_notes) VALUES (:id,:order,:machine,0,'PASSED',:actor,'Fixture',:actor,CURRENT_TIMESTAMP,'Passed fixture')")
            .param("id",passed).param("order",completed).param("machine",UUID.fromString(f.machine())).param("actor",UUID.fromString(f.owner())).update();
        db.sql("UPDATE manufacturing_orders SET status='COMPLETED',completed_at=CURRENT_TIMESTAMP,tested_at=CURRENT_TIMESTAMP,passed_trial_id=:trial,delivery_at=:due WHERE id=:id")
            .param("id",completed).param("trial",passed).param("due",java.sql.Timestamp.from(due)).update();
        var first=CompletableFuture.runAsync(()->reminderEvaluator.evaluate(completed)); var second=CompletableFuture.runAsync(()->reminderEvaluator.evaluate(completed)); first.join(); second.join();
        assertEquals(2,eventCount("DELIVERY_OVERDUE")); assertEquals(1,eventCount("DELIVERY_DUE_SOON"));
        when(workClock.instant()).thenReturn(due.minusSeconds(60)); reminderEvaluator.evaluate(completed); assertEquals(1,eventCount("DELIVERY_DUE_SOON"));
        db.sql("UPDATE manufacturing_orders SET delivered_at=CURRENT_TIMESTAMP,delivered_by=:actor,delivery_notes='Delivered fixture',delivery_at=:due WHERE id=:id")
            .param("id",completed).param("actor",UUID.fromString(f.owner())).param("due",java.sql.Timestamp.from(due.minusSeconds(86400))).update();
        reminderEvaluator.evaluate(completed);
        for(String state:List.of("DRAFT","CANCELLED")) {
            UUID excluded=UUID.fromString(newOrder(orderInput("notification-"+state,f)).path("id").asText());
            db.sql("UPDATE manufacturing_orders SET status=:status,opened_at=:opened,cancelled_at=:cancelled,cancellation_reason=:reason,delivery_at=:due WHERE id=:id")
                .param("id",excluded).param("status",state)
                .param("opened",state.equals("DRAFT")?null:java.sql.Timestamp.from(due),java.sql.Types.TIMESTAMP)
                .param("cancelled",state.equals("CANCELLED")?java.sql.Timestamp.from(due):null,java.sql.Types.TIMESTAMP)
                .param("reason",state.equals("CANCELLED")?"Cancelled fixture":null,java.sql.Types.VARCHAR).param("due",java.sql.Timestamp.from(due.minusSeconds(86400))).update();
            reminderEvaluator.evaluate(excluded);
        }
        assertEquals(2,eventCount("DELIVERY_OVERDUE"));
    }
    @Test void fullPermissionRolesAndOverridesRoundTripWithoutArbitraryCollectionLimits() throws Exception {
        assertTrue(Permissions.ALL.size()>50);
        JsonNode administrator=null;
        for(var role:admin.read(admin.call("GET","/roles",null,false),200)) if(role.path("name").asText().equals("Administrator")) administrator=role;
        assertNotNull(administrator);
        var body=new HashMap<String,Object>(Map.of("name","Administrator","description","Updated full-permission role","permissions",Permissions.ALL,"version",administrator.path("version").asLong()));
        var updated=admin.read(admin.call("PUT","/roles/"+administrator.path("id").asText(),body,true),200);
        assertEquals(Permissions.ALL.size(),updated.path("permissions").size());
        assertEquals("Updated full-permission role",updated.path("description").asText());
        body.put("name","Full permission regression"); body.put("version",0);
        var created=admin.read(admin.call("POST","/roles",body,true),201);
        assertEquals(Permissions.ALL.size(),created.path("permissions").size());
        var unknown=new ArrayList<>(Permissions.ALL); unknown.add("unknown.permission"); body.put("permissions",unknown);
        assertEquals("UNKNOWN_PERMISSION",admin.read(admin.call("PUT","/roles/"+created.path("id").asText(),body,true),400).path("code").asText());
        var overrides=new HashMap<String,Boolean>(); Permissions.ALL.forEach(p->overrides.put(p,true));
        var userInput=input("all-overrides@test.local",List.of(),overrides);
        var user=admin.read(admin.call("POST","/users",userInput,true),201);
        assertEquals(Permissions.ALL.size(),user.path("overrides").size());
        overrides.put("unknown.permission",true); userInput.put("email","unknown-overrides@test.local");
        assertEquals("UNKNOWN_PERMISSION",admin.read(admin.call("POST","/users",userInput,true),400).path("code").asText());
        assertEquals(0L,db.sql("SELECT count(*) FROM users WHERE email='unknown-overrides@test.local'").query(Long.class).single());
    }
    @Test void templateVersionsBeyondBoxedCacheCompareByValueAndKeepConcurrentProtection() throws Exception {
        var body=new HashMap<String,Object>(Map.of("version",0,"name","Version boundary checklist","active",true,"checks",List.of(Map.of("label","Safety","criterion","Guard verified"))));
        String id=admin.read(admin.call("POST","/operations/templates",body,true),201).path("id").asText();
        for(long version:List.of(0L,127L,128L,1024L)) {
            db.sql("UPDATE operations_templates SET version=:version WHERE id=:id").param("id",UUID.fromString(id)).param("version",version).update();
            body.put("version",version);
            var updated=admin.read(admin.call("PUT","/operations/templates/"+id,body,true),200);
            assertEquals(version+1,updated.path("version").asLong());
            assertEquals("STALE_VERSION",admin.read(admin.call("PUT","/operations/templates/"+id,body,true),409).path("code").asText());
        }
        body.put("version",1025L);
        var first=CompletableFuture.supplyAsync(()->catalogStatus("PUT","/operations/templates/"+id,body));
        var second=CompletableFuture.supplyAsync(()->catalogStatus("PUT","/operations/templates/"+id,body));
        assertEquals(Set.of(200,409),Set.of(first.join(),second.join()));
    }
    @Test void attachmentMetadataNeverReadsBinaryColumnsAndLargeDownloadsRemainIntact() throws Exception {
        var fixture=defectFixture("metadata-columns");
        String pdf="%PDF-1.4\n"+" ".repeat(2*1024*1024)+"\n%%EOF";
        var file=admin.read(upload(admin,fixture.order(),pdf),201);
        admin.read(upload(admin,fixture.order(),pdf+"\nsecond"),201);
        admin.read(upload(admin,fixture.order(),pdf+"\nthird"),201);
        String role="review_metadata_"+UUID.randomUUID().toString().replace("-","");
        assertTrue(role.matches("review_metadata_[a-f0-9]{32}"));
        db.sql("CREATE ROLE "+role+" NOLOGIN").update();
        try {
            db.sql("GRANT USAGE ON SCHEMA public TO "+role).update();
            db.sql("GRANT SELECT ON users,user_roles,role_permissions,user_permission_overrides,manufacturing_orders TO "+role).update();
            db.sql("GRANT SELECT (id,target_kind,target_id,filename,content_type,size_bytes,version,uploaded_at,uploaded_by,deleted) ON resource_files TO "+role).update();
            UUID actor=UUID.fromString(admin.read(admin.call("GET","/auth/me",null,false),200).path("id").asText());
            new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status->{
                db.sql("SET LOCAL ROLE "+role).update();
                // The database itself rejects SELECT * or any attempt to read content here.
                var metadata=fileService.list(com.easyback.files.FileService.Kind.ORDER,UUID.fromString(fixture.order()),actor);
                assertEquals(3,metadata.size()); assertTrue(metadata.stream().allMatch(f->f.sizeBytes()>2*1024*1024));
            });
        } finally {
            db.sql("REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM "+role).update();
            db.sql("REVOKE SELECT (id,target_kind,target_id,filename,content_type,size_bytes,version,uploaded_at,uploaded_by,deleted) ON resource_files FROM "+role).update();
            db.sql("REVOKE USAGE ON SCHEMA public FROM "+role).update();
            db.sql("DROP ROLE "+role).update();
        }
        var listed=admin.call("GET","/files?kind=ORDER&targetId="+fixture.order(),null,false);
        assertEquals(3,admin.read(listed,200).size()); assertTrue(listed.body().length()<4096);
        assertFalse(listed.body().contains("\"content\""));
        assertEquals(pdf,fixture.worker().client().call("GET","/files/"+file.path("id").asText(),null,false).body());
    }
    int catalogStatus(String method, String path, Object body) {
        try { return admin.call(method, path, body, true).statusCode(); } catch (Exception e) { throw new RuntimeException(e); }
    }
}

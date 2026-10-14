package com.easyback.catalog;

import static com.easyback.catalog.CatalogData.*;
import com.easyback.identity.ApiException;
import java.math.BigDecimal;
import java.sql.Types;
import java.util.*;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
public class CatalogService {
    private final JdbcClient db;
    public CatalogService(JdbcClient db) { this.db = db; }
    private static final RowMapper<Model> MODEL = (r, n) -> new Model(r.getObject("id", UUID.class), r.getString("code"),
        r.getString("name"), r.getString("description"), r.getBoolean("active"), r.getLong("version"));
    private static final RowMapper<Part> PART = (r, n) -> new Part(r.getObject("id", UUID.class), r.getString("code"),
        r.getString("name"), r.getString("description"), Kind.valueOf(r.getString("kind")), Shape.valueOf(r.getString("shape")),
        StockUnit.valueOf(r.getString("stock_unit")), r.getBigDecimal("length_mm"), r.getBigDecimal("width_mm"),
        r.getBigDecimal("diameter_mm"), r.getBigDecimal("default_install_minutes"), r.getBoolean("active"), r.getLong("version"));
    private static final String MACHINE_SQL = """
        SELECT m.*, mo.code model_code, mo.name model_name FROM machines m JOIN machine_models mo ON mo.id = m.model_id
        """;
    private static final RowMapper<Machine> MACHINE = (r, n) -> new Machine(r.getObject("id", UUID.class), r.getString("serial_number"),
        r.getObject("model_id", UUID.class), r.getString("model_code"), r.getString("model_name"), r.getString("name"),
        r.getString("location"), r.getString("notes"), r.getBoolean("active"), r.getLong("version"));
    private void guard() { db.sql("SELECT id FROM catalog_guard WHERE id = 1 FOR UPDATE").query(Integer.class).single(); }
    private static ApiException missing() { return new ApiException(404, "NOT_FOUND", "Record not found."); }
    private static void rule(boolean valid, String code) {
        if (!valid) throw new ApiException(409, code, "The requested change is not allowed.");
    }
    private static void version(long current, long supplied) { rule(current == supplied, "STALE_VERSION"); }
    private static String code(String value) { return value.toUpperCase(Locale.ROOT); }
    private void audit(UUID actor, String action, UUID target) {
        db.sql("INSERT INTO audit_logs(id, actor_id, action, target_id) VALUES (:id, :actor, :action, :target)")
            .param("id", UUID.randomUUID()).param("actor", actor).param("action", action).param("target", target).update();
    }
    public List<Model> models() { return db.sql("SELECT * FROM machine_models ORDER BY code").query(MODEL).list(); }
    private Model model(UUID id) {
        return db.sql("SELECT * FROM machine_models WHERE id = :id").param("id", id).query(MODEL).optional().orElseThrow(CatalogService::missing);
    }
    @Transactional
    public Model saveModel(UUID id, ModelInput input, UUID actor) {
        guard(); boolean creating = id == null;
        if (creating) {
            id = UUID.randomUUID();
            db.sql("INSERT INTO machine_models(id, code, name, description, active) VALUES (:id, :code, :name, :description, :active)")
                .param("id", id).param("code", code(input.code())).param("name", input.name().trim())
                .param("description", input.description().trim()).param("active", input.active()).update();
        } else {
            var old = model(id); version(old.version(), input.version()); rule(old.code().equals(code(input.code())), "CODE_IMMUTABLE");
            db.sql("UPDATE machine_models SET name=:name, description=:description, active=:active, version=version+1 WHERE id=:id")
                .param("id", id).param("name", input.name().trim()).param("description", input.description().trim()).param("active", input.active()).update();
        }
        audit(actor, creating ? "MODEL_CREATED" : "MODEL_UPDATED", id); return model(id);
    }
    public List<Part> parts() { return db.sql("SELECT * FROM parts ORDER BY code").query(PART).list(); }
    private Part part(UUID id) {
        return db.sql("SELECT * FROM parts WHERE id=:id").param("id", id).query(PART).optional().orElseThrow(CatalogService::missing);
    }
    private static boolean same(BigDecimal a, BigDecimal b) { return a == null ? b == null : b != null && a.compareTo(b) == 0; }
    private static boolean sameSpec(Part old, PartInput input) {
        return old.kind() == input.kind() && old.shape() == input.shape() && old.stockUnit() == input.stockUnit()
            && same(old.lengthMm(), input.lengthMm()) && same(old.widthMm(), input.widthMm()) && same(old.diameterMm(), input.diameterMm());
    }
    private static void validatePart(PartInput p) {
        boolean dimensions = switch (p.shape()) {
            case STANDARD -> p.lengthMm() == null && p.widthMm() == null && p.diameterMm() == null;
            case RECTANGULAR -> p.lengthMm() != null && p.widthMm() != null && p.diameterMm() == null;
            case ROUND -> p.lengthMm() != null && p.diameterMm() != null && p.widthMm() == null;
        };
        boolean kind = p.kind() == Kind.SERIALIZED_PIECE
            ? p.stockUnit() == StockUnit.PIECE && p.defaultInstallMinutes().signum() > 0
            : p.stockUnit() != StockUnit.PIECE && p.shape() != Shape.STANDARD && p.defaultInstallMinutes().signum() == 0;
        if (!dimensions || !kind || (p.stockUnit() == StockUnit.AREA && p.shape() != Shape.RECTANGULAR))
            throw new ApiException(400, "INVALID_SPECIFICATION", "Shape, dimensions, stock unit and installation time must agree.");
    }
    @Transactional
    public Part savePart(UUID id, PartInput p, UUID actor) {
        guard(); validatePart(p); boolean creating = id == null;
        if (creating) id = UUID.randomUUID();
        else {
            var old = part(id); version(old.version(), p.version()); rule(old.code().equals(code(p.code())), "CODE_IMMUTABLE");
            if (!sameSpec(old, p)) rule(db.sql("SELECT (SELECT count(*) FROM bom_items WHERE part_id=:id)+(SELECT count(*) FROM stock_pieces WHERE part_id=:id)+(SELECT count(*) FROM raw_units WHERE part_id=:id)").param("id", id).query(Long.class).single() == 0, "SPECIFICATION_IN_USE");
        }
        var sql = creating ? """
            INSERT INTO parts(id, code, name, description, kind, shape, stock_unit, length_mm, width_mm, diameter_mm, default_install_minutes, active)
            VALUES (:id, :code, :name, :description, :kind, :shape, :unit, :length, :width, :diameter, :minutes, :active)
            """ : """
            UPDATE parts SET name=:name, description=:description, kind=:kind, shape=:shape, stock_unit=:unit,
                length_mm=:length, width_mm=:width, diameter_mm=:diameter, default_install_minutes=:minutes,
                active=:active, version=version+1 WHERE id=:id AND code=:code
            """;
        db.sql(sql).param("id", id).param("code", code(p.code())).param("name", p.name().trim()).param("description", p.description().trim())
            .param("kind", p.kind().name()).param("shape", p.shape().name()).param("unit", p.stockUnit().name())
            .param("length", p.lengthMm(), Types.NUMERIC).param("width", p.widthMm(), Types.NUMERIC).param("diameter", p.diameterMm(), Types.NUMERIC)
            .param("minutes", p.defaultInstallMinutes()).param("active", p.active()).update();
        audit(actor, creating ? "PART_CREATED" : "PART_UPDATED", id); return part(id);
    }
    public List<Machine> machines() { return db.sql(MACHINE_SQL + " ORDER BY m.serial_number").query(MACHINE).list(); }
    private Machine machine(UUID id) {
        return db.sql(MACHINE_SQL + " WHERE m.id=:id").param("id", id).query(MACHINE).optional().orElseThrow(CatalogService::missing);
    }
    @Transactional
    public Machine saveMachine(UUID id, MachineInput p, UUID actor) {
        guard(); boolean creating = id == null;
        if (creating) {
            rule(model(p.modelId()).active(), "MODEL_INACTIVE"); id = UUID.randomUUID();
            db.sql("INSERT INTO machines(id, serial_number, model_id, name, location, notes, active) VALUES (:id, :serial, :model, :name, :location, :notes, :active)")
                .param("id", id).param("serial", code(p.serialNumber())).param("model", p.modelId()).param("name", p.name().trim())
                .param("location", p.location().trim()).param("notes", p.notes().trim()).param("active", p.active()).update();
        } else {
            var old = machine(id); version(old.version(), p.version());
            rule(old.serialNumber().equals(code(p.serialNumber())) && old.modelId().equals(p.modelId()), "MACHINE_IDENTITY_IMMUTABLE");
            db.sql("UPDATE machines SET name=:name, location=:location, notes=:notes, active=:active, version=version+1 WHERE id=:id")
                .param("id", id).param("name", p.name().trim()).param("location", p.location().trim()).param("notes", p.notes().trim()).param("active", p.active()).update();
        }
        audit(actor, creating ? "MACHINE_CREATED" : "MACHINE_UPDATED", id); return machine(id);
    }
    public List<Bom> boms(UUID modelId) {
        model(modelId);
        return db.sql("SELECT id FROM bom_versions WHERE model_id=:id AND machine_id IS NULL ORDER BY revision DESC").param("id", modelId).query(UUID.class).list().stream().map(this::bom).toList();
    }
    public List<Bom> machineBoms(UUID machineId) {
        machine(machineId);
        return db.sql("SELECT id FROM bom_versions WHERE machine_id=:id ORDER BY revision DESC").param("id", machineId).query(UUID.class).list().stream().map(this::bom).toList();
    }
    public Bom bom(UUID id) {
        var head = db.sql("SELECT * FROM bom_versions WHERE id=:id").param("id", id).query((r,n) -> new Bom(id,
            r.getObject("model_id", UUID.class), r.getObject("machine_id", UUID.class), r.getInt("revision"), r.getString("status"), r.getLong("version"),
            r.getTimestamp("published_at") == null ? null : r.getTimestamp("published_at").toInstant(), List.of()))
            .optional().orElseThrow(CatalogService::missing);
        var items = db.sql("SELECT * FROM bom_items WHERE bom_id=:id ORDER BY position_code").param("id", id).query((r,n) -> new Item(
            r.getObject("id", UUID.class), r.getString("position_code"), r.getObject("part_id", UUID.class), r.getString("part_code"),
            r.getString("part_name"), Shape.valueOf(r.getString("shape")), r.getBigDecimal("length_mm"), r.getBigDecimal("width_mm"),
            r.getBigDecimal("diameter_mm"), r.getInt("quantity"), r.getBigDecimal("expected_install_minutes"), r.getString("notes"))).list();
        return new Bom(id, head.modelId(), head.machineId(), head.revision(), head.status(), head.version(), head.publishedAt(), items);
    }
    @Transactional
    public Bom createDraft(UUID modelId, DraftInput input, UUID actor) {
        return createDraft(modelId, null, input, actor);
    }
    @Transactional
    public Bom createMachineDraft(UUID machineId, DraftInput input, UUID actor) {
        guard(); var machine = machine(machineId); rule(machine.active(), "MACHINE_INACTIVE");
        return createDraft(machine.modelId(), machineId, input, actor);
    }
    private Bom createDraft(UUID modelId, UUID machineId, DraftInput input, UUID actor) {
        guard();
        if (machineId == null) rule(model(modelId).active(), "MODEL_INACTIVE");
        String owner = machineId == null ? "model_id=:owner AND machine_id IS NULL" : "machine_id=:owner";
        UUID ownerId = machineId == null ? modelId : machineId;
        rule(db.sql("SELECT count(*) FROM bom_versions WHERE " + owner + " AND status='DRAFT'").param("owner", ownerId).query(Long.class).single() == 0, "DRAFT_EXISTS");
        UUID source = input.sourceBomId();
        if (source == null) source = db.sql("SELECT id FROM bom_versions WHERE " + owner + " AND status='PUBLISHED' ORDER BY revision DESC LIMIT 1")
            .param("owner", ownerId).query(UUID.class).optional().orElse(null);
        if (source != null) { var old = bom(source); rule(old.modelId().equals(modelId) && old.status().equals("PUBLISHED")
            && (old.machineId() == null || old.machineId().equals(machineId)), "INVALID_BOM_SOURCE"); }
        int revision = db.sql("SELECT COALESCE(MAX(revision),0)+1 FROM bom_versions WHERE " + owner).param("owner", ownerId).query(Integer.class).single();
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO bom_versions(id, model_id, machine_id, revision, status) VALUES (:id, :model, :machine, :revision, 'DRAFT')")
            .param("id", id).param("model", modelId).param("machine", machineId, Types.OTHER).param("revision", revision).update();
        if (source != null) for (var item : bom(source).items()) {
            db.sql("""
                INSERT INTO bom_items(id,bom_id,position_code,part_id,quantity,expected_install_minutes,notes,part_code,part_name,shape,length_mm,width_mm,diameter_mm)
                SELECT :newId,:newBom,position_code,part_id,quantity,expected_install_minutes,notes,part_code,part_name,shape,length_mm,width_mm,diameter_mm
                FROM bom_items WHERE id=:source
                """).param("newId", UUID.randomUUID()).param("newBom", id).param("source", item.id()).update();
        }
        audit(actor, "BOM_DRAFT_CREATED", id); return bom(id);
    }
    @Transactional
    public Part createLinkedPart(LinkedPartInput input, UUID actor) {
        guard();
        var part = savePart(null, input.part(), actor);
        var draft = boms(input.modelId()).stream().filter(b -> b.status().equals("DRAFT")).findFirst()
            .orElseGet(() -> createDraft(input.modelId(), new DraftInput(null), actor));
        saveItem(draft.id(), null, new ItemInput(input.positionCode(), part.id(), input.quantity(), part.defaultInstallMinutes(), "", draft.version()), actor);
        return part;
    }
    private void activeOwner(Bom bom) {
        if (bom.machineId() == null) rule(model(bom.modelId()).active(), "MODEL_INACTIVE");
        else rule(machine(bom.machineId()).active(), "MACHINE_INACTIVE");
    }
    private Bom editable(UUID id, long supplied) {
        var value = bom(id); version(value.version(), supplied); rule(value.status().equals("DRAFT"), "BOM_LOCKED"); return value;
    }
    private void touch(UUID id) { db.sql("UPDATE bom_versions SET version=version+1 WHERE id=:id").param("id", id).update(); }
    @Transactional
    public Bom saveItem(UUID bomId, UUID itemId, ItemInput p, UUID actor) {
        guard(); var head = editable(bomId, p.version()); activeOwner(head);
        var part = part(p.partId()); rule(part.active(), "PART_INACTIVE"); rule(part.kind() == Kind.SERIALIZED_PIECE, "RAW_MATERIAL_NOT_INSTALLABLE");
        if (itemId != null) rule(head.items().stream().anyMatch(i -> i.id().equals(itemId)), "ITEM_NOT_IN_BOM");
        UUID id = itemId == null ? UUID.randomUUID() : itemId;
        var sql = itemId == null ? """
            INSERT INTO bom_items(id,bom_id,position_code,part_id,quantity,expected_install_minutes,notes,part_code,part_name,shape,length_mm,width_mm,diameter_mm)
            VALUES (:id,:bom,:position,:part,:quantity,:minutes,:notes,:code,:name,:shape,:length,:width,:diameter)
            """ : """
            UPDATE bom_items SET position_code=:position,part_id=:part,quantity=:quantity,expected_install_minutes=:minutes,notes=:notes,
                part_code=:code,part_name=:name,shape=:shape,length_mm=:length,width_mm=:width,diameter_mm=:diameter WHERE id=:id AND bom_id=:bom
            """;
        db.sql(sql).param("id", id).param("bom", bomId).param("position", code(p.positionCode())).param("part", p.partId()).param("quantity", p.quantity())
            .param("minutes", p.expectedInstallMinutes()).param("notes", p.notes().trim()).param("code", part.code()).param("name", part.name())
            .param("shape", part.shape().name()).param("length", part.lengthMm(), Types.NUMERIC).param("width", part.widthMm(), Types.NUMERIC)
            .param("diameter", part.diameterMm(), Types.NUMERIC).update();
        touch(bomId); audit(actor, itemId == null ? "BOM_ITEM_ADDED" : "BOM_ITEM_UPDATED", bomId); return bom(bomId);
    }
    @Transactional
    public Bom removeItem(UUID bomId, UUID itemId, long supplied, UUID actor) {
        guard(); activeOwner(editable(bomId, supplied));
        if (db.sql("DELETE FROM bom_items WHERE id=:item AND bom_id=:bom").param("item", itemId).param("bom", bomId).update() == 0) throw missing();
        touch(bomId); audit(actor, "BOM_ITEM_REMOVED", bomId); return bom(bomId);
    }
    @Transactional
    public Bom publish(UUID id, long supplied, UUID actor) {
        guard(); var draft = editable(id, supplied); activeOwner(draft);
        rule(!draft.items().isEmpty(), "BOM_EMPTY");
        for (var item : draft.items()) rule(part(item.partId()).active(), "PART_INACTIVE");
        db.sql("UPDATE bom_versions SET status='PUBLISHED', published_at=CURRENT_TIMESTAMP, version=version+1 WHERE id=:id").param("id", id).update();
        audit(actor, "BOM_PUBLISHED", id); return bom(id);
    }
}

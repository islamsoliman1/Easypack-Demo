CREATE TABLE catalog_guard (id INTEGER PRIMARY KEY CHECK (id = 1));
INSERT INTO catalog_guard VALUES (1);

CREATE TABLE machine_models (
    id UUID PRIMARY KEY, code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL, description VARCHAR(1000) NOT NULL,
    active BOOLEAN NOT NULL, version BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE parts (
    id UUID PRIMARY KEY, code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL, description VARCHAR(1000) NOT NULL,
    kind VARCHAR(24) NOT NULL CHECK (kind IN ('SERIALIZED_PIECE', 'MEASURED_STOCK')),
    shape VARCHAR(16) NOT NULL CHECK (shape IN ('STANDARD', 'RECTANGULAR', 'ROUND')),
    stock_unit VARCHAR(8) NOT NULL CHECK (stock_unit IN ('PIECE', 'LENGTH', 'AREA')),
    length_mm NUMERIC(9,3), width_mm NUMERIC(9,3), diameter_mm NUMERIC(9,3),
    default_install_minutes NUMERIC(9,2) NOT NULL,
    active BOOLEAN NOT NULL, version BIGINT NOT NULL DEFAULT 0,
    CHECK ((shape = 'STANDARD' AND length_mm IS NULL AND width_mm IS NULL AND diameter_mm IS NULL)
        OR (shape = 'RECTANGULAR' AND length_mm > 0 AND width_mm > 0 AND diameter_mm IS NULL AND length_mm IS NOT NULL AND width_mm IS NOT NULL)
        OR (shape = 'ROUND' AND length_mm > 0 AND diameter_mm > 0 AND width_mm IS NULL AND length_mm IS NOT NULL AND diameter_mm IS NOT NULL)),
    CHECK ((kind = 'SERIALIZED_PIECE' AND stock_unit = 'PIECE' AND default_install_minutes > 0)
        OR (kind = 'MEASURED_STOCK' AND stock_unit IN ('LENGTH', 'AREA') AND shape <> 'STANDARD' AND default_install_minutes = 0)),
    CHECK (stock_unit <> 'AREA' OR shape = 'RECTANGULAR')
);
CREATE TABLE machines (
    id UUID PRIMARY KEY, serial_number VARCHAR(64) NOT NULL UNIQUE,
    model_id UUID NOT NULL REFERENCES machine_models(id), name VARCHAR(120) NOT NULL,
    location VARCHAR(120) NOT NULL, notes VARCHAR(1000) NOT NULL,
    active BOOLEAN NOT NULL, version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX ix_machines_model ON machines(model_id);
CREATE TABLE bom_versions (
    id UUID PRIMARY KEY, model_id UUID NOT NULL REFERENCES machine_models(id),
    revision INTEGER NOT NULL CHECK (revision > 0),
    status VARCHAR(12) NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED')),
    version BIGINT NOT NULL DEFAULT 0, published_at TIMESTAMPTZ,
    UNIQUE (model_id, revision),
    CHECK ((status = 'DRAFT' AND published_at IS NULL) OR (status = 'PUBLISHED' AND published_at IS NOT NULL))
);
CREATE UNIQUE INDEX ix_one_bom_draft ON bom_versions(model_id) WHERE status = 'DRAFT';
CREATE TABLE bom_items (
    id UUID PRIMARY KEY, bom_id UUID NOT NULL REFERENCES bom_versions(id),
    position_code VARCHAR(40) NOT NULL, part_id UUID NOT NULL REFERENCES parts(id),
    quantity INTEGER NOT NULL CHECK (quantity BETWEEN 1 AND 10000),
    expected_install_minutes NUMERIC(9,2) NOT NULL CHECK (expected_install_minutes > 0),
    notes VARCHAR(1000) NOT NULL,
    part_code VARCHAR(64) NOT NULL, part_name VARCHAR(120) NOT NULL,
    shape VARCHAR(16) NOT NULL, length_mm NUMERIC(9,3), width_mm NUMERIC(9,3), diameter_mm NUMERIC(9,3),
    UNIQUE (bom_id, position_code)
);
CREATE INDEX ix_bom_items_part ON bom_items(part_id);

-- Extend existing full security-administration roles, never department or read-only roles.
WITH full_roles AS (
    SELECT role_id FROM role_permissions WHERE permission IN
      ('users.view','users.create','users.update','users.deactivate','users.reset-password',
       'roles.view','roles.create','roles.update','permissions.assign')
    GROUP BY role_id HAVING COUNT(*) = 9
)
INSERT INTO role_permissions(role_id, permission)
SELECT role_id, permission FROM full_roles CROSS JOIN
    (VALUES ('catalog.view'), ('catalog.manage'), ('machines.view'), ('machines.manage'), ('boms.view'), ('boms.manage')) p(permission)
ON CONFLICT DO NOTHING;
UPDATE roles SET version = version + 1 WHERE id IN
    (SELECT role_id FROM role_permissions WHERE permission = 'catalog.manage');
UPDATE users SET version = version + 1 WHERE id IN
    (SELECT ur.user_id FROM user_roles ur JOIN role_permissions rp ON rp.role_id = ur.role_id WHERE rp.permission = 'catalog.manage');

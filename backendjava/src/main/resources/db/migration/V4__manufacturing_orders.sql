CREATE TABLE manufacturing_orders (
    id UUID PRIMARY KEY, code VARCHAR(64) NOT NULL UNIQUE, title VARCHAR(120) NOT NULL,
    machine_id UUID NOT NULL REFERENCES machines(id), bom_id UUID NOT NULL REFERENCES bom_versions(id),
    responsible_id UUID NOT NULL REFERENCES users(id), delivery_at TIMESTAMPTZ NOT NULL,
    notes VARCHAR(1000) NOT NULL, status VARCHAR(12) NOT NULL CHECK (status IN ('DRAFT','OPEN','CANCELLED')),
    machine_serial VARCHAR(64) NOT NULL, machine_name VARCHAR(120) NOT NULL,
    model_name VARCHAR(120) NOT NULL, bom_revision INTEGER NOT NULL,
    version BIGINT NOT NULL DEFAULT 0, created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, opened_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ, cancellation_reason VARCHAR(1000),
    CHECK ((status = 'DRAFT' AND opened_at IS NULL AND cancelled_at IS NULL AND cancellation_reason IS NULL)
        OR (status = 'OPEN' AND opened_at IS NOT NULL AND cancelled_at IS NULL AND cancellation_reason IS NULL)
        OR (status = 'CANCELLED' AND cancelled_at IS NOT NULL AND length(trim(cancellation_reason)) > 0 AND cancellation_reason IS NOT NULL))
);
CREATE INDEX ix_orders_responsible ON manufacturing_orders(responsible_id, created_at DESC);
CREATE INDEX ix_orders_machine ON manufacturing_orders(machine_id);
CREATE TABLE order_requirements (
    id UUID PRIMARY KEY, order_id UUID NOT NULL REFERENCES manufacturing_orders(id),
    source_item_id UUID NOT NULL REFERENCES bom_items(id), part_id UUID NOT NULL REFERENCES parts(id),
    position_code VARCHAR(40) NOT NULL, part_code VARCHAR(64) NOT NULL, part_name VARCHAR(120) NOT NULL,
    shape VARCHAR(16) NOT NULL, length_mm NUMERIC(9,3), width_mm NUMERIC(9,3), diameter_mm NUMERIC(9,3),
    quantity INTEGER NOT NULL CHECK (quantity BETWEEN 1 AND 10000),
    expected_install_minutes NUMERIC(9,2) NOT NULL CHECK (expected_install_minutes > 0), notes VARCHAR(1000) NOT NULL,
    UNIQUE(order_id, position_code)
);

WITH full_roles AS (
    SELECT role_id FROM role_permissions WHERE permission IN
      ('users.view','users.create','users.update','users.deactivate','users.reset-password',
       'roles.view','roles.create','roles.update','permissions.assign')
    GROUP BY role_id HAVING COUNT(*) = 9
), granted AS (
    INSERT INTO role_permissions(role_id, permission)
    SELECT role_id, permission FROM full_roles CROSS JOIN (VALUES
      ('orders.view'),('orders.view-assigned'),('orders.manage'),('orders.release'),('orders.cancel')) p(permission)
    ON CONFLICT DO NOTHING RETURNING role_id
)
UPDATE roles SET version=version+1 WHERE id IN (SELECT role_id FROM granted);
UPDATE users SET version=version+1 WHERE id IN (
    SELECT ur.user_id FROM user_roles ur JOIN role_permissions rp ON rp.role_id=ur.role_id WHERE rp.permission='orders.manage'
);

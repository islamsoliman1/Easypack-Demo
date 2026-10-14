CREATE TABLE stock_pieces (
    id UUID PRIMARY KEY, serial_number VARCHAR(64) NOT NULL UNIQUE,
    part_id UUID NOT NULL REFERENCES parts(id), location VARCHAR(120) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('AVAILABLE','RESERVED','ISSUED','QUARANTINED')),
    requirement_id UUID REFERENCES order_requirements(id), recipient_id UUID REFERENCES users(id),
    version BIGINT NOT NULL DEFAULT 0, received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((status IN ('AVAILABLE','QUARANTINED') AND requirement_id IS NULL AND recipient_id IS NULL)
        OR (status='RESERVED' AND requirement_id IS NOT NULL AND recipient_id IS NULL)
        OR (status='ISSUED' AND requirement_id IS NOT NULL AND recipient_id IS NOT NULL))
);
CREATE INDEX ix_stock_available ON stock_pieces(part_id,received_at,id) WHERE status='AVAILABLE';
CREATE INDEX ix_stock_requirement ON stock_pieces(requirement_id);
CREATE TABLE stock_movements (
    id UUID PRIMARY KEY, piece_id UUID NOT NULL REFERENCES stock_pieces(id),
    action VARCHAR(24) NOT NULL CHECK (action IN ('RECEIVED','RESERVED','UNRESERVED','ISSUED','RETURNED_GOOD','RETURNED_DEFECTIVE','RESTORED')),
    requirement_id UUID REFERENCES order_requirements(id), recipient_id UUID REFERENCES users(id),
    actor_id UUID NOT NULL REFERENCES users(id), reference VARCHAR(120) NOT NULL, notes VARCHAR(1000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX ix_stock_movements_piece ON stock_movements(piece_id,created_at);
CREATE TABLE preparation_requests (
    id UUID PRIMARY KEY, requirement_id UUID NOT NULL UNIQUE REFERENCES order_requirements(id),
    requested_quantity INTEGER NOT NULL CHECK (requested_quantity > 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('REQUESTED','IN_PROGRESS','FULFILLED','CANCELLED')),
    notes VARCHAR(1000) NOT NULL, requested_by UUID NOT NULL REFERENCES users(id),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, version BIGINT NOT NULL DEFAULT 0
);
WITH full_roles AS (
    SELECT role_id FROM role_permissions WHERE permission IN
      ('users.view','users.create','users.update','users.deactivate','users.reset-password',
       'roles.view','roles.create','roles.update','permissions.assign')
    GROUP BY role_id HAVING COUNT(*)=9
), granted AS (
    INSERT INTO role_permissions(role_id,permission)
    SELECT role_id,permission FROM full_roles CROSS JOIN (VALUES
      ('stock.view'),('stock.receive'),('stock.reserve'),('stock.issue'),('stock.return'),('preparation.manage')) p(permission)
    ON CONFLICT DO NOTHING RETURNING role_id
)
UPDATE roles SET version=version+1 WHERE id IN (SELECT role_id FROM granted);
UPDATE users SET version=version+1 WHERE id IN (
    SELECT ur.user_id FROM user_roles ur JOIN role_permissions rp ON rp.role_id=ur.role_id WHERE rp.permission='stock.receive'
);

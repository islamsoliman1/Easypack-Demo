ALTER TABLE manufacturing_orders DROP CONSTRAINT manufacturing_orders_status_check, DROP CONSTRAINT manufacturing_orders_check;
ALTER TABLE manufacturing_orders ALTER COLUMN status TYPE VARCHAR(20);
ALTER TABLE manufacturing_orders ADD CONSTRAINT manufacturing_orders_status_check CHECK(status IN ('DRAFT','OPEN','IN_PROGRESS','AWAITING_TESTING','CANCELLED'));
ALTER TABLE manufacturing_orders ADD CONSTRAINT manufacturing_orders_check CHECK(
    (status='DRAFT' AND opened_at IS NULL AND cancelled_at IS NULL AND cancellation_reason IS NULL)
    OR (status IN ('OPEN','IN_PROGRESS','AWAITING_TESTING') AND opened_at IS NOT NULL AND cancelled_at IS NULL AND cancellation_reason IS NULL)
    OR (status='CANCELLED' AND cancelled_at IS NOT NULL AND cancellation_reason IS NOT NULL AND length(trim(cancellation_reason))>0));
ALTER TABLE manufacturing_orders ADD COLUMN assembly_completed_at TIMESTAMPTZ;
ALTER TABLE machines ADD COLUMN configuration_version BIGINT NOT NULL DEFAULT 0;
CREATE TABLE assembly_tasks (
    id UUID PRIMARY KEY, requirement_id UUID NOT NULL REFERENCES order_requirements(id),
    occurrence INTEGER NOT NULL CHECK(occurrence>0), worker_id UUID REFERENCES users(id),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','IN_PROGRESS','INSTALLED')),
    version BIGINT NOT NULL DEFAULT 0, started_at TIMESTAMPTZ,
    UNIQUE(requirement_id,occurrence),
    CHECK ((status='PENDING' AND started_at IS NULL) OR (status<>'PENDING' AND started_at IS NOT NULL AND worker_id IS NOT NULL))
);
CREATE INDEX ix_tasks_worker ON assembly_tasks(worker_id);
INSERT INTO assembly_tasks(id,requirement_id,occurrence)
SELECT gen_random_uuid(),r.id,n FROM order_requirements r JOIN manufacturing_orders o ON o.id=r.order_id
CROSS JOIN LATERAL generate_series(1,r.quantity) n WHERE o.status='OPEN';
ALTER TABLE stock_pieces ADD COLUMN task_id UUID REFERENCES assembly_tasks(id);
ALTER TABLE stock_pieces DROP CONSTRAINT stock_pieces_status_check, DROP CONSTRAINT stock_pieces_check;
ALTER TABLE stock_pieces ADD CONSTRAINT stock_pieces_status_check CHECK(status IN ('AVAILABLE','RESERVED','ISSUED','INSTALLED','QUARANTINED'));
ALTER TABLE stock_pieces ADD CONSTRAINT stock_pieces_check CHECK(
    (status IN ('AVAILABLE','QUARANTINED') AND requirement_id IS NULL AND recipient_id IS NULL AND task_id IS NULL)
    OR (status='RESERVED' AND requirement_id IS NOT NULL AND recipient_id IS NULL AND task_id IS NULL)
    OR (status='ISSUED' AND requirement_id IS NOT NULL AND recipient_id IS NOT NULL)
    OR (status='INSTALLED' AND requirement_id IS NOT NULL AND recipient_id IS NOT NULL AND task_id IS NOT NULL));
CREATE UNIQUE INDEX ix_piece_task ON stock_pieces(task_id) WHERE task_id IS NOT NULL;
ALTER TABLE stock_movements DROP CONSTRAINT stock_movements_action_check;
ALTER TABLE stock_movements ADD CONSTRAINT stock_movements_action_check CHECK(action IN
    ('RECEIVED','RESERVED','UNRESERVED','ISSUED','RETURNED_GOOD','RETURNED_DEFECTIVE','RESTORED','INSTALLED','UNINSTALLED'));
CREATE TABLE installations (
    id UUID PRIMARY KEY, task_id UUID NOT NULL REFERENCES assembly_tasks(id), piece_id UUID NOT NULL REFERENCES stock_pieces(id),
    machine_id UUID NOT NULL REFERENCES machines(id), position_code VARCHAR(40) NOT NULL, occurrence INTEGER NOT NULL,
    worker_id UUID NOT NULL REFERENCES users(id), started_at TIMESTAMPTZ NOT NULL,
    installed_by UUID NOT NULL REFERENCES users(id), installed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    install_notes VARCHAR(1000) NOT NULL, removed_by UUID REFERENCES users(id), removed_at TIMESTAMPTZ, removal_reason VARCHAR(1000),
    CHECK((removed_at IS NULL AND removed_by IS NULL AND removal_reason IS NULL)
        OR (removed_at IS NOT NULL AND removed_by IS NOT NULL AND removal_reason IS NOT NULL AND length(trim(removal_reason))>0))
);
CREATE UNIQUE INDEX ix_installed_piece ON installations(piece_id) WHERE removed_at IS NULL;
CREATE UNIQUE INDEX ix_installed_task ON installations(task_id) WHERE removed_at IS NULL;
CREATE UNIQUE INDEX ix_installed_slot ON installations(machine_id,position_code,occurrence) WHERE removed_at IS NULL;
CREATE TABLE assembly_events (
    id UUID PRIMARY KEY, task_id UUID NOT NULL REFERENCES assembly_tasks(id),
    action VARCHAR(24) NOT NULL CHECK(action IN ('ASSIGNED','UNASSIGNED','STARTED','PIECE_ISSUED','PIECE_RETURNED','INSTALLED','UNINSTALLED')),
    worker_id UUID REFERENCES users(id), actor_id UUID NOT NULL REFERENCES users(id), piece_id UUID REFERENCES stock_pieces(id),
    notes VARCHAR(1000) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX ix_assembly_events_task ON assembly_events(task_id,created_at);
WITH full_roles AS (
    SELECT role_id FROM role_permissions WHERE permission IN
      ('users.view','users.create','users.update','users.deactivate','users.reset-password',
       'roles.view','roles.create','roles.update','permissions.assign')
    GROUP BY role_id HAVING COUNT(*)=9
), granted AS (
    INSERT INTO role_permissions(role_id,permission)
    SELECT role_id,permission FROM full_roles CROSS JOIN (VALUES
      ('tasks.view'),('tasks.view-assigned'),('tasks.assign'),('tasks.work'),('tasks.uninstall')) p(permission)
    ON CONFLICT DO NOTHING RETURNING role_id
)
UPDATE roles SET version=version+1 WHERE id IN (SELECT role_id FROM granted);
UPDATE users SET version=version+1 WHERE id IN (
    SELECT ur.user_id FROM user_roles ur JOIN role_permissions rp ON rp.role_id=ur.role_id WHERE rp.permission='tasks.assign'
);

ALTER TABLE manufacturing_orders DROP CONSTRAINT manufacturing_orders_status_check, DROP CONSTRAINT manufacturing_orders_check;
ALTER TABLE manufacturing_orders ADD CONSTRAINT manufacturing_orders_status_check CHECK(status IN
  ('DRAFT','OPEN','IN_PROGRESS','AWAITING_TESTING','TESTING','REWORK','COMPLETED','CANCELLED'));
ALTER TABLE manufacturing_orders ADD CONSTRAINT manufacturing_orders_check CHECK(
  (status='DRAFT' AND opened_at IS NULL AND cancelled_at IS NULL AND cancellation_reason IS NULL)
  OR (status IN ('OPEN','IN_PROGRESS','AWAITING_TESTING','TESTING','REWORK','COMPLETED') AND opened_at IS NOT NULL AND cancelled_at IS NULL AND cancellation_reason IS NULL)
  OR (status='CANCELLED' AND cancelled_at IS NOT NULL AND cancellation_reason IS NOT NULL AND length(trim(cancellation_reason))>0));
ALTER TABLE manufacturing_orders ADD COLUMN rework_required BOOLEAN NOT NULL DEFAULT false,
  ADD COLUMN tested_at TIMESTAMPTZ, ADD COLUMN completed_at TIMESTAMPTZ, ADD COLUMN delivered_at TIMESTAMPTZ,
  ADD COLUMN delivered_by UUID REFERENCES users(id), ADD COLUMN delivery_notes VARCHAR(1000), ADD COLUMN passed_trial_id UUID;
CREATE TABLE operations_trials (
  id UUID PRIMARY KEY, order_id UUID NOT NULL REFERENCES manufacturing_orders(id), machine_id UUID NOT NULL REFERENCES machines(id),
  configuration_version BIGINT NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'RUNNING' CHECK(status IN ('RUNNING','PASSED','FAILED','INVALIDATED','ABORTED')),
  version BIGINT NOT NULL DEFAULT 0, started_by UUID NOT NULL REFERENCES users(id), started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  start_notes VARCHAR(1000) NOT NULL, finished_by UUID REFERENCES users(id), finished_at TIMESTAMPTZ, result_notes VARCHAR(1000),
  resolved_by UUID REFERENCES users(id), resolved_at TIMESTAMPTZ, resolution_notes VARCHAR(1000),
  CHECK((status='RUNNING' AND finished_by IS NULL AND finished_at IS NULL AND result_notes IS NULL)
    OR (status<>'RUNNING' AND finished_by IS NOT NULL AND finished_at IS NOT NULL AND result_notes IS NOT NULL AND length(trim(result_notes))>0)),
  CHECK((resolved_by IS NULL AND resolved_at IS NULL AND resolution_notes IS NULL)
    OR (status IN ('FAILED','INVALIDATED','ABORTED') AND resolved_by IS NOT NULL AND resolved_at IS NOT NULL AND resolution_notes IS NOT NULL AND length(trim(resolution_notes))>0))
);
CREATE UNIQUE INDEX ix_running_trial_machine ON operations_trials(machine_id) WHERE status='RUNNING';
CREATE UNIQUE INDEX ix_running_trial_order ON operations_trials(order_id) WHERE status='RUNNING';
CREATE INDEX ix_trials_order ON operations_trials(order_id,started_at);
CREATE TABLE operations_checks (
  id UUID PRIMARY KEY, trial_id UUID NOT NULL REFERENCES operations_trials(id), ordinal INTEGER NOT NULL CHECK(ordinal>0),
  label VARCHAR(200) NOT NULL CHECK(length(trim(label))>0), criterion VARCHAR(500) NOT NULL CHECK(length(trim(criterion))>0),
  result VARCHAR(12) NOT NULL DEFAULT 'PENDING' CHECK(result IN ('PENDING','PASS','FAIL')), notes VARCHAR(1000) NOT NULL DEFAULT '',
  UNIQUE(trial_id,ordinal)
);
CREATE TABLE operations_configuration (
  trial_id UUID NOT NULL REFERENCES operations_trials(id), installation_id UUID NOT NULL REFERENCES installations(id),
  position_code VARCHAR(40) NOT NULL, occurrence INTEGER NOT NULL, serial_number VARCHAR(64) NOT NULL,
  part_code VARCHAR(64) NOT NULL, part_name VARCHAR(120) NOT NULL, PRIMARY KEY(trial_id,installation_id)
);
CREATE TABLE operations_events (
  id UUID PRIMARY KEY, order_id UUID NOT NULL REFERENCES manufacturing_orders(id), trial_id UUID REFERENCES operations_trials(id),
  actor_id UUID NOT NULL REFERENCES users(id), action VARCHAR(24) NOT NULL CHECK(action IN ('STARTED','PASSED','FAILED','INVALIDATED','ABORTED','RESOLVED','DELIVERED')),
  notes VARCHAR(1000) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX ix_operations_events_order ON operations_events(order_id,created_at);
ALTER TABLE manufacturing_orders ADD CONSTRAINT fk_passed_trial FOREIGN KEY(passed_trial_id) REFERENCES operations_trials(id);
ALTER TABLE manufacturing_orders ADD CONSTRAINT order_completion_check CHECK(
  (status='COMPLETED' AND completed_at IS NOT NULL AND tested_at IS NOT NULL AND passed_trial_id IS NOT NULL AND NOT rework_required)
  OR (status<>'COMPLETED' AND completed_at IS NULL AND passed_trial_id IS NULL));
ALTER TABLE manufacturing_orders ADD CONSTRAINT order_delivery_check CHECK(
  (delivered_at IS NULL AND delivered_by IS NULL AND delivery_notes IS NULL)
  OR (status='COMPLETED' AND delivered_at IS NOT NULL AND delivered_by IS NOT NULL AND delivery_notes IS NOT NULL AND length(trim(delivery_notes))>0));
WITH full_roles AS (
  SELECT role_id FROM role_permissions WHERE permission IN
    ('users.view','users.create','users.update','users.deactivate','users.reset-password','roles.view','roles.create','roles.update','permissions.assign')
  GROUP BY role_id HAVING count(*)=9
), granted AS (
  INSERT INTO role_permissions(role_id,permission)
  SELECT role_id,permission FROM full_roles CROSS JOIN (VALUES
    ('operations.view'),('operations.start'),('operations.finish'),('operations.resolve'),('operations.deliver')) p(permission)
  ON CONFLICT DO NOTHING RETURNING role_id
)
UPDATE roles SET version=version+1 WHERE id IN (SELECT role_id FROM granted);
UPDATE users SET version=version+1 WHERE id IN (
  SELECT ur.user_id FROM user_roles ur JOIN role_permissions rp ON rp.role_id=ur.role_id WHERE rp.permission='operations.view'
);

ALTER TABLE stock_pieces DROP CONSTRAINT stock_pieces_status_check, DROP CONSTRAINT stock_pieces_check;
ALTER TABLE stock_pieces ADD CONSTRAINT stock_pieces_status_check CHECK(status IN
  ('AVAILABLE','RESERVED','ISSUED','INSTALLED','REJECTED','QUARANTINED','IN_REPAIR','REPAIRED','SCRAPPED'));
ALTER TABLE stock_pieces ADD CONSTRAINT stock_pieces_check CHECK(
  (status IN ('AVAILABLE','QUARANTINED','IN_REPAIR','REPAIRED','SCRAPPED') AND requirement_id IS NULL AND recipient_id IS NULL AND task_id IS NULL)
  OR (status='RESERVED' AND requirement_id IS NOT NULL AND recipient_id IS NULL AND task_id IS NULL)
  OR (status='ISSUED' AND requirement_id IS NOT NULL AND recipient_id IS NOT NULL)
  OR (status IN ('INSTALLED','REJECTED') AND requirement_id IS NOT NULL AND recipient_id IS NOT NULL AND task_id IS NOT NULL));
ALTER TABLE stock_movements DROP CONSTRAINT stock_movements_action_check;
ALTER TABLE stock_movements ADD CONSTRAINT stock_movements_action_check CHECK(action IN
  ('RECEIVED','RESERVED','UNRESERVED','ISSUED','RETURNED_GOOD','RETURNED_DEFECTIVE','RESTORED','INSTALLED','UNINSTALLED',
   'REJECTED','DEFECT_RECEIVED','REPAIR_STARTED','REPAIR_COMPLETED','REPAIR_RECEIVED','SCRAPPED'));
ALTER TABLE assembly_events DROP CONSTRAINT assembly_events_action_check;
ALTER TABLE assembly_events ADD CONSTRAINT assembly_events_action_check CHECK(action IN
  ('ASSIGNED','UNASSIGNED','STARTED','PIECE_ISSUED','PIECE_RETURNED','INSTALLED','UNINSTALLED','REJECTED'));
CREATE TABLE defect_cases (
  id UUID PRIMARY KEY, task_id UUID NOT NULL REFERENCES assembly_tasks(id), piece_id UUID NOT NULL REFERENCES stock_pieces(id),
  reported_by UUID NOT NULL REFERENCES users(id), reason VARCHAR(1000) NOT NULL CHECK(length(trim(reason))>0),
  status VARCHAR(20) NOT NULL DEFAULT 'REPORTED' CHECK(status IN ('REPORTED','QUARANTINED','IN_REPAIR','REPAIRED','RETURNED','SCRAPPED')),
  version BIGINT NOT NULL DEFAULT 0, reported_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX ix_active_defect_piece ON defect_cases(piece_id) WHERE status NOT IN ('RETURNED','SCRAPPED');
CREATE INDEX ix_defects_reporter ON defect_cases(reported_by);
CREATE TABLE defect_events (
  id UUID PRIMARY KEY, defect_id UUID NOT NULL REFERENCES defect_cases(id), actor_id UUID NOT NULL REFERENCES users(id),
  action VARCHAR(24) NOT NULL CHECK(action IN ('REPORTED','RECEIVED','REPAIR_STARTED','REPAIR_COMPLETED','ACCEPTED','SCRAPPED')),
  notes VARCHAR(1000) NOT NULL CHECK(length(trim(notes))>0), created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX ix_defect_events_case ON defect_events(defect_id,created_at);
WITH full_roles AS (
  SELECT role_id FROM role_permissions WHERE permission IN
    ('users.view','users.create','users.update','users.deactivate','users.reset-password','roles.view','roles.create','roles.update','permissions.assign')
  GROUP BY role_id HAVING count(*)=9
), granted AS (
  INSERT INTO role_permissions(role_id,permission)
  SELECT role_id,permission FROM full_roles CROSS JOIN (VALUES ('tasks.reject'),('defects.view'),('defects.manage'),('defects.scrap')) p(permission)
  ON CONFLICT DO NOTHING RETURNING role_id
)
UPDATE roles SET version=version+1 WHERE id IN (SELECT role_id FROM granted);
UPDATE users SET version=version+1 WHERE id IN (
  SELECT ur.user_id FROM user_roles ur JOIN role_permissions rp ON rp.role_id=ur.role_id WHERE rp.permission='defects.view'
);

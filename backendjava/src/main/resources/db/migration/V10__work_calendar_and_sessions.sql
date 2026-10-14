CREATE TABLE work_calendar (
 id INTEGER PRIMARY KEY CHECK(id=1), version BIGINT NOT NULL DEFAULT 0,
 weekdays INTEGER[] NOT NULL DEFAULT ARRAY[1,2,3,4,6,7],
 breaks JSONB NOT NULL DEFAULT '[]', breaks_confirmed BOOLEAN NOT NULL DEFAULT false
);
INSERT INTO work_calendar(id) VALUES(1);
CREATE TABLE work_days (
 day DATE PRIMARY KEY, working BOOLEAN NOT NULL, extended BOOLEAN NOT NULL DEFAULT false,
 breaks JSONB NOT NULL, breaks_confirmed BOOLEAN NOT NULL, version BIGINT NOT NULL DEFAULT 0,
 reason VARCHAR(1000) NOT NULL DEFAULT '', updated_by UUID REFERENCES users(id)
);
CREATE TABLE work_sessions (
 id UUID PRIMARY KEY, task_id UUID NOT NULL REFERENCES assembly_tasks(id), worker_id UUID NOT NULL REFERENCES users(id),
 day DATE NOT NULL REFERENCES work_days(day), started_at TIMESTAMPTZ NOT NULL, ended_at TIMESTAMPTZ,
 reason VARCHAR(1000), CHECK(ended_at IS NULL OR ended_at>=started_at),
 CHECK((ended_at IS NULL AND reason IS NULL) OR (ended_at IS NOT NULL AND reason IS NOT NULL AND length(trim(reason))>0))
);
CREATE UNIQUE INDEX ix_work_session_worker ON work_sessions(worker_id) WHERE ended_at IS NULL;
CREATE UNIQUE INDEX ix_work_session_task ON work_sessions(task_id) WHERE ended_at IS NULL;
CREATE INDEX ix_work_session_task_history ON work_sessions(task_id,started_at);
ALTER TABLE assembly_events DROP CONSTRAINT assembly_events_action_check;
ALTER TABLE assembly_events ADD CONSTRAINT assembly_events_action_check CHECK(action IN
 ('ASSIGNED','UNASSIGNED','PIECE_ISSUED','PIECE_RETURNED','STARTED','INSTALLED','UNINSTALLED','REJECTED','PAUSED','RESUMED','TIME_STOPPED'));
WITH full_roles AS (
 SELECT role_id FROM role_permissions WHERE permission IN ('users.view','users.create','users.update','users.deactivate','users.reset-password','roles.view','roles.create','roles.update','permissions.assign') GROUP BY role_id HAVING count(*)=9
), granted AS (
 INSERT INTO role_permissions(role_id,permission) SELECT role_id,permission FROM full_roles CROSS JOIN
 (VALUES('shifts.view'),('shifts.manage'),('shifts.extend'),('time.manage')) p(permission) ON CONFLICT DO NOTHING RETURNING role_id
) UPDATE roles SET version=version+1 WHERE id IN(SELECT role_id FROM granted);
UPDATE users SET version=version+1 WHERE id IN(SELECT user_id FROM user_roles ur JOIN role_permissions rp ON rp.role_id=ur.role_id WHERE rp.permission='shifts.view');

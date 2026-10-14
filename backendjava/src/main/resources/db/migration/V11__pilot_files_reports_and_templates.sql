CREATE TABLE resource_files (
 id UUID PRIMARY KEY, target_kind VARCHAR(16) NOT NULL CHECK(target_kind IN('ORDER','MACHINE','PART','DEFECT')),
 target_id UUID NOT NULL, filename VARCHAR(180) NOT NULL, content_type VARCHAR(80) NOT NULL,
 size_bytes INTEGER NOT NULL CHECK(size_bytes>0 AND size_bytes<=10485760), sha256 VARCHAR(64) NOT NULL,
 content BYTEA NOT NULL, version BIGINT NOT NULL DEFAULT 0, deleted BOOLEAN NOT NULL DEFAULT false,
 uploaded_by UUID NOT NULL REFERENCES users(id), uploaded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CHECK(octet_length(content)=size_bytes)
);
CREATE INDEX ix_resource_target ON resource_files(target_kind,target_id,uploaded_at);
CREATE UNIQUE INDEX ix_resource_hash ON resource_files(target_kind,target_id,sha256) WHERE NOT deleted;
CREATE TABLE operations_templates (
 id UUID PRIMARY KEY, name VARCHAR(120) NOT NULL, checks JSONB NOT NULL,
 active BOOLEAN NOT NULL DEFAULT true, version BIGINT NOT NULL DEFAULT 0,
 updated_by UUID NOT NULL REFERENCES users(id), updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX ix_operations_template_name ON operations_templates(lower(name));
WITH full_roles AS (
 SELECT role_id FROM role_permissions WHERE permission IN('users.view','users.create','users.update','users.deactivate','users.reset-password','roles.view','roles.create','roles.update','permissions.assign') GROUP BY role_id HAVING count(*)=9
), granted AS (
 INSERT INTO role_permissions(role_id,permission) SELECT role_id,permission FROM full_roles CROSS JOIN
 (VALUES('files.manage'),('reports.view'),('reports.export'),('operations.templates.manage')) p(permission) ON CONFLICT DO NOTHING RETURNING role_id
) UPDATE roles SET version=version+1 WHERE id IN(SELECT role_id FROM granted);
UPDATE users SET version=version+1 WHERE id IN(SELECT user_id FROM user_roles ur JOIN role_permissions rp ON rp.role_id=ur.role_id WHERE rp.permission='reports.view');

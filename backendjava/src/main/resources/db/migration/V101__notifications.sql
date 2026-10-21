ALTER TABLE assembly_tasks ADD COLUMN assignment_epoch bigint NOT NULL DEFAULT 0;

CREATE TABLE notifications (
    id uuid PRIMARY KEY,
    type varchar(64) NOT NULL,
    order_code varchar(64) NOT NULL,
    sample boolean NOT NULL DEFAULT false,
    severity varchar(16) NOT NULL CHECK (severity IN ('INFO','WARNING','URGENT')),
    actor_id uuid REFERENCES users(id) ON DELETE SET NULL,
    order_id uuid NOT NULL REFERENCES manufacturing_orders(id) ON DELETE CASCADE,
    task_id uuid REFERENCES assembly_tasks(id) ON DELETE CASCADE,
    preparation_id uuid REFERENCES preparation_requests(id) ON DELETE CASCADE,
    defect_id uuid REFERENCES defect_cases(id) ON DELETE CASCADE,
    trial_id uuid REFERENCES operations_trials(id) ON DELETE CASCADE,
    assignment_epoch bigint,
    dedupe_key varchar(240) NOT NULL UNIQUE,
    created_at timestamptz NOT NULL
);
CREATE TABLE notification_recipients (
    notification_id uuid NOT NULL REFERENCES notifications(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    scope_type varchar(16) NOT NULL CHECK (scope_type IN ('ORDER','TASK','DEFECT','PREPARATION','OPERATIONS')),
    scope_id uuid NOT NULL,
    required_permissions text[] NOT NULL DEFAULT '{}',
    owner_fallback boolean NOT NULL DEFAULT false,
    title varchar(200) NOT NULL,
    body varchar(500) NOT NULL,
    created_at timestamptz NOT NULL,
    read_at timestamptz,
    PRIMARY KEY(notification_id,user_id)
);
CREATE INDEX notification_inbox ON notification_recipients(user_id,created_at DESC,notification_id DESC);
CREATE INDEX notification_unread ON notification_recipients(user_id,created_at DESC,notification_id DESC) WHERE read_at IS NULL;

-- One batch-oriented effective-permission definition; explicit denies override role grants.
CREATE VIEW notification_user_permissions AS
SELECT u.id, coalesce(array_agg(DISTINCT p.permission::text) FILTER (WHERE p.permission IS NOT NULL),ARRAY[]::text[]) permissions
FROM users u LEFT JOIN (
    SELECT ur.user_id,rp.permission FROM user_roles ur JOIN role_permissions rp ON rp.role_id=ur.role_id
    WHERE NOT EXISTS (SELECT 1 FROM user_permission_overrides x WHERE x.user_id=ur.user_id AND x.permission=rp.permission AND NOT x.allowed)
    UNION SELECT user_id,permission FROM user_permission_overrides WHERE allowed
) p ON p.user_id=u.id WHERE u.active GROUP BY u.id;

-- Applied BEFORE pagination and shared by count, list and both read mutations.
CREATE VIEW visible_notifications AS
SELECT n.id,n.type,n.severity,n.created_at,r.user_id,r.read_at,r.title,r.body,n.order_code,n.sample,
    CASE WHEN access.allowed THEN r.scope_type ELSE 'ORDER' END scope_type,
    CASE WHEN access.allowed THEN r.scope_id ELSE o.id END scope_id
FROM notification_recipients r JOIN notifications n ON n.id=r.notification_id
JOIN notification_user_permissions p ON p.id=r.user_id
JOIN manufacturing_orders o ON o.id=n.order_id
LEFT JOIN assembly_tasks t ON t.id=n.task_id
CROSS JOIN LATERAL (SELECT
    (p.permissions @> r.required_permissions OR (n.type='DEFECT_REPORTED' AND r.scope_type='DEFECT'
        AND (p.permissions @> ARRAY['defects.view','orders.view','defects.manage']::text[]
            OR p.permissions @> ARRAY['defects.view','orders.view','stock.view','stock.return']::text[]))) AND CASE r.scope_type
    WHEN 'ORDER' THEN r.scope_id=o.id AND ('orders.view'=ANY(p.permissions) OR (o.responsible_id=r.user_id AND 'orders.view-assigned'=ANY(p.permissions)))
    WHEN 'TASK' THEN r.scope_id=t.id AND t.worker_id=r.user_id AND t.assignment_epoch=n.assignment_epoch
        AND ('tasks.view-assigned'=ANY(p.permissions) OR p.permissions @> ARRAY['tasks.view','orders.view']::text[])
    WHEN 'DEFECT' THEN r.scope_id=n.defect_id AND p.permissions @> ARRAY['defects.view','orders.view']::text[]
    WHEN 'PREPARATION' THEN r.scope_id=n.preparation_id AND 'orders.view'=ANY(p.permissions)
        AND ('stock.view'=ANY(p.permissions) OR 'preparation.manage'=ANY(p.permissions))
    WHEN 'OPERATIONS' THEN r.scope_id=o.id AND 'operations.view'=ANY(p.permissions)
    ELSE false END allowed) access
WHERE access.allowed OR (r.owner_fallback AND o.responsible_id=r.user_id
    AND ('orders.view'=ANY(p.permissions) OR 'orders.view-assigned'=ANY(p.permissions)));

-- Independent of V100 so ordinary integration-test profiles may also migrate V101.
CREATE TABLE notification_demo_seed (id integer PRIMARY KEY CHECK(id=1), completed boolean NOT NULL DEFAULT false);
INSERT INTO notification_demo_seed(id) VALUES(1);

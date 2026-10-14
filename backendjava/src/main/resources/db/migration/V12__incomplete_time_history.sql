ALTER TABLE assembly_tasks ADD COLUMN incomplete_time_history BOOLEAN NOT NULL DEFAULT false;

-- Preserve evidence even when legacy work was resumed, returned or reassigned after V10.
WITH tracking AS (
    SELECT installed_on AT TIME ZONE current_setting('TimeZone') AS introduced_at
    FROM flyway_schema_history WHERE version='10' AND success
)
UPDATE assembly_tasks t SET incomplete_time_history=true
WHERE (
    NOT EXISTS(SELECT 1 FROM work_sessions w WHERE w.task_id=t.id)
    AND (t.started_at IS NOT NULL
        OR EXISTS(SELECT 1 FROM installations i WHERE i.task_id=t.id)
        OR EXISTS(SELECT 1 FROM assembly_events e WHERE e.task_id=t.id AND e.action IN('STARTED','INSTALLED','UNINSTALLED')))
) OR EXISTS (
    SELECT 1 FROM tracking c WHERE t.started_at<c.introduced_at
        OR EXISTS(SELECT 1 FROM assembly_events e WHERE e.task_id=t.id AND e.action IN('STARTED','INSTALLED','UNINSTALLED') AND e.created_at<c.introduced_at)
        OR EXISTS(SELECT 1 FROM installations i WHERE i.task_id=t.id AND i.started_at<c.introduced_at)
);

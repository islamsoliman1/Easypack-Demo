ALTER TABLE machines ADD CONSTRAINT uq_machine_model UNIQUE (id, model_id);
ALTER TABLE bom_versions ADD COLUMN machine_id UUID;
ALTER TABLE bom_versions ADD CONSTRAINT fk_bom_machine_model
    FOREIGN KEY (machine_id, model_id) REFERENCES machines(id, model_id);
ALTER TABLE bom_versions DROP CONSTRAINT bom_versions_model_id_revision_key;
DROP INDEX ix_one_bom_draft;
CREATE UNIQUE INDEX ix_model_bom_revision ON bom_versions(model_id, revision) WHERE machine_id IS NULL;
CREATE UNIQUE INDEX ix_machine_bom_revision ON bom_versions(machine_id, revision) WHERE machine_id IS NOT NULL;
CREATE UNIQUE INDEX ix_one_bom_draft ON bom_versions(model_id) WHERE status = 'DRAFT' AND machine_id IS NULL;
CREATE UNIQUE INDEX ix_one_machine_bom_draft ON bom_versions(machine_id) WHERE status = 'DRAFT' AND machine_id IS NOT NULL;

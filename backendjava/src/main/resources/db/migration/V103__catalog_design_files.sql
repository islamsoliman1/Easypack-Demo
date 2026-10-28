ALTER TABLE resource_files DROP CONSTRAINT resource_files_target_kind_check;
ALTER TABLE resource_files ADD CONSTRAINT resource_files_target_kind_check
 CHECK(target_kind IN('ORDER','MACHINE','PART','DEFECT','MODEL'));
ALTER TABLE resource_files ADD COLUMN purpose VARCHAR(16) NOT NULL DEFAULT 'ATTACHMENT'
 CHECK(purpose IN('ATTACHMENT','DESIGN','PREVIEW'));

-- One source may be uploaded for different uses, for example an image attachment
-- and the selected preview, without discarding the existing attachment.
DROP INDEX ix_resource_hash;
CREATE UNIQUE INDEX ix_resource_hash ON resource_files(target_kind,target_id,purpose,sha256) WHERE NOT deleted;

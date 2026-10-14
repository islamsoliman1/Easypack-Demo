CREATE TABLE raw_units (
    id UUID PRIMARY KEY, code VARCHAR(64) NOT NULL UNIQUE, part_id UUID NOT NULL REFERENCES parts(id),
    length_mm NUMERIC(9,3) NOT NULL CHECK(length_mm>0), width_mm NUMERIC(9,3) CHECK(width_mm>0),
    location VARCHAR(120) NOT NULL, status VARCHAR(16) NOT NULL CHECK(status IN ('AVAILABLE','ISSUED','CONSUMED','SCRAPPED')),
    preparation_id UUID REFERENCES preparation_requests(id), parent_id UUID REFERENCES raw_units(id),
    version BIGINT NOT NULL DEFAULT 0, received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((status='ISSUED' AND preparation_id IS NOT NULL) OR status<>'ISSUED'),
    CHECK (status<>'AVAILABLE' OR preparation_id IS NULL)
);
CREATE INDEX ix_raw_part_status ON raw_units(part_id,status);
CREATE INDEX ix_raw_preparation ON raw_units(preparation_id);
CREATE TABLE raw_cuts (
    id UUID PRIMARY KEY, source_id UUID NOT NULL UNIQUE REFERENCES raw_units(id), preparation_id UUID NOT NULL REFERENCES preparation_requests(id),
    output_length_mm NUMERIC(9,3) NOT NULL CHECK(output_length_mm>0), output_width_mm NUMERIC(9,3),
    columns_count INTEGER NOT NULL CHECK(columns_count>0), rotated BOOLEAN NOT NULL,
    quantity INTEGER NOT NULL CHECK(quantity BETWEEN 1 AND 200), output_amount NUMERIC(24,6) NOT NULL CHECK(output_amount>0),
    returned_amount NUMERIC(24,6) NOT NULL CHECK(returned_amount>=0), scrap_amount NUMERIC(24,6) NOT NULL CHECK(scrap_amount>=0),
    actor_id UUID NOT NULL REFERENCES users(id), notes VARCHAR(1000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE raw_cut_outputs (
    cut_id UUID NOT NULL REFERENCES raw_cuts(id), piece_id UUID NOT NULL UNIQUE REFERENCES stock_pieces(id),
    PRIMARY KEY(cut_id,piece_id)
);
CREATE TABLE raw_movements (
    id UUID PRIMARY KEY, unit_id UUID NOT NULL REFERENCES raw_units(id),
    action VARCHAR(24) NOT NULL CHECK(action IN ('RECEIVED','ISSUED','RETURNED','CONSUMED','REMNANT_RETURNED','SCRAPPED')),
    preparation_id UUID REFERENCES preparation_requests(id), actor_id UUID NOT NULL REFERENCES users(id),
    amount NUMERIC(24,6) NOT NULL CHECK(amount>0), reference VARCHAR(120) NOT NULL, notes VARCHAR(1000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX ix_raw_movements_unit ON raw_movements(unit_id,created_at);
WITH full_roles AS (
    SELECT role_id FROM role_permissions WHERE permission IN
      ('users.view','users.create','users.update','users.deactivate','users.reset-password',
       'roles.view','roles.create','roles.update','permissions.assign')
    GROUP BY role_id HAVING COUNT(*)=9
), granted AS (
    INSERT INTO role_permissions(role_id,permission)
    SELECT role_id,permission FROM full_roles CROSS JOIN (VALUES ('raw.receive'),('raw.issue'),('raw.cut')) p(permission)
    ON CONFLICT DO NOTHING RETURNING role_id
)
UPDATE roles SET version=version+1 WHERE id IN (SELECT role_id FROM granted);
UPDATE users SET version=version+1 WHERE id IN (
    SELECT ur.user_id FROM user_roles ur JOIN role_permissions rp ON rp.role_id=ur.role_id WHERE rp.permission='raw.receive'
);

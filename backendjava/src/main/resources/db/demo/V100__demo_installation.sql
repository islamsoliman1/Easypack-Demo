-- Refuse to attach the public demo to an existing application database.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM users) THEN
        RAISE EXCEPTION 'The public demo requires a NEW, EMPTY database. Existing users were found.';
    END IF;
END $$;

CREATE TABLE demo_installation (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    seeded BOOLEAN NOT NULL DEFAULT FALSE
);
INSERT INTO demo_installation(id) VALUES (1);

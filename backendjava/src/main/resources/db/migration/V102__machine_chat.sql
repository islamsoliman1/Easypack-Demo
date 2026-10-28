CREATE TABLE machine_messages (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    machine_id UUID NOT NULL REFERENCES machines(id),
    sender_id UUID NOT NULL REFERENCES users(id),
    body VARCHAR(2000) NOT NULL CHECK(length(trim(body)) > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX ix_machine_messages ON machine_messages(machine_id, id DESC);
CREATE TABLE machine_chat_reads (
    machine_id UUID NOT NULL REFERENCES machines(id),
    user_id UUID NOT NULL REFERENCES users(id),
    last_message_id BIGINT NOT NULL CHECK(last_message_id > 0),
    PRIMARY KEY(machine_id, user_id)
);

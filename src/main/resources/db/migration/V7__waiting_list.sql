-- People who wanted a day that was full. Nobody gets messaged by the system: the staff sees
-- the list on that day's agenda and, when someone cancels, calls the first one.
CREATE TABLE waiting (
    id              BIGSERIAL PRIMARY KEY,
    service_id      BIGINT      NOT NULL REFERENCES service (id) ON DELETE CASCADE,
    -- null: anyone who does the service
    professional_id BIGINT      REFERENCES professional (id) ON DELETE CASCADE,
    day             DATE        NOT NULL,
    client_name     TEXT        NOT NULL CHECK (length(trim(client_name)) > 0),
    client_phone    TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- asking twice for the same day keeps the first place in the line
    UNIQUE (day, service_id, client_phone)
);
CREATE INDEX waiting_day ON waiting (day, created_at);

-- people from the business who can log in to manage it
CREATE TABLE staff (
    id            BIGSERIAL PRIMARY KEY,
    name          TEXT        NOT NULL CHECK (length(trim(name)) > 0),
    email         TEXT        NOT NULL,
    password_hash TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Ana@x.com and ana@x.com are the same person
CREATE UNIQUE INDEX staff_email ON staff (lower(email));

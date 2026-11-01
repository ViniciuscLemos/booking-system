-- times are stored as the business's local time (timestamp without time zone),
-- the same way the rules work with them. The zone lives in the config.

CREATE TABLE service (
    id           BIGSERIAL PRIMARY KEY,
    name         TEXT     NOT NULL CHECK (length(trim(name)) > 0),
    description  TEXT,
    duration_min INTEGER  NOT NULL CHECK (duration_min > 0),
    buffer_min   INTEGER  NOT NULL DEFAULT 0 CHECK (buffer_min >= 0),
    price_cents  BIGINT   NOT NULL DEFAULT 0 CHECK (price_cents >= 0),
    active       BOOLEAN  NOT NULL DEFAULT TRUE
);

-- ISO weekday: 1 = Monday ... 7 = Sunday. A day with no rows is closed
CREATE TABLE working_hours (
    id          BIGSERIAL PRIMARY KEY,
    day_of_week SMALLINT NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
    starts      TIME     NOT NULL,
    ends        TIME     NOT NULL,
    CHECK (starts < ends)
);

-- replaces the weekday on that date; a row with no times means closed
CREATE TABLE date_override (
    id     BIGSERIAL PRIMARY KEY,
    day    DATE NOT NULL,
    starts TIME,
    ends   TIME,
    CHECK ((starts IS NULL AND ends IS NULL) OR starts < ends)
);
CREATE INDEX date_override_day ON date_override (day);

CREATE TABLE time_off (
    id     BIGSERIAL PRIMARY KEY,
    starts TIMESTAMP NOT NULL,
    ends   TIMESTAMP NOT NULL,
    reason TEXT,
    CHECK (starts < ends)
);

CREATE TABLE booking (
    id            BIGSERIAL PRIMARY KEY,
    service_id    BIGINT    NOT NULL REFERENCES service (id),
    client_name   TEXT      NOT NULL,
    client_phone  TEXT      NOT NULL,
    starts        TIMESTAMP NOT NULL,
    ends          TIMESTAMP NOT NULL,
    -- ends + the service's buffer, copied here so changing the service later
    -- doesn't move bookings that already exist
    blocked_until TIMESTAMP NOT NULL,
    status        TEXT      NOT NULL DEFAULT 'CONFIRMED' CHECK (status IN ('CONFIRMED', 'CANCELLED')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (starts < ends AND ends <= blocked_until),

    -- the last line of defense: even if two requests pass the rules at the same
    -- moment, Postgres refuses the second overlapping row. '[)' so 10:00-10:30
    -- and 10:30-11:00 don't count as overlapping
    CONSTRAINT booking_no_overlap EXCLUDE USING gist (
        tsrange(starts, blocked_until, '[)') WITH &&
    ) WHERE (status = 'CONFIRMED')
);

-- the day's calendar is loaded by time window, so this is the query that runs the most
CREATE INDEX booking_confirmed_starts ON booking (starts) WHERE status = 'CONFIRMED';

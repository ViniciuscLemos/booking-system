-- More than one person taking clients: each booking belongs to someone, and two bookings
-- can only overlap if they're with different people.

-- lets an exclusion constraint mix "=" on a plain column with "&&" on a time range
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE professional (
    id     BIGSERIAL PRIMARY KEY,
    name   TEXT    NOT NULL CHECK (length(trim(name)) > 0),
    active BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE UNIQUE INDEX professional_name ON professional (lower(name));

-- everything booked so far was with the one person doing the work
INSERT INTO professional (name) VALUES ('Me');

ALTER TABLE booking ADD COLUMN professional_id BIGINT REFERENCES professional (id);
UPDATE booking SET professional_id = (SELECT min(id) FROM professional);
ALTER TABLE booking ALTER COLUMN professional_id SET NOT NULL;

-- time off of one person; NULL is still the whole place (a holiday, a renovation)
ALTER TABLE time_off ADD COLUMN professional_id BIGINT REFERENCES professional (id) ON DELETE CASCADE;

-- the same last line of defense as before, now per person: Rui at 10:00 and Bia at 10:00
-- are fine, Rui twice at 10:00 is refused by Postgres whatever the code does
ALTER TABLE booking DROP CONSTRAINT booking_no_overlap;
ALTER TABLE booking ADD CONSTRAINT booking_no_overlap EXCLUDE USING gist (
    professional_id WITH =,
    tsrange(starts, blocked_until, '[)') WITH &&
) WHERE (status = 'CONFIRMED');

-- the client cancels through a link with a random token. Only its SHA-256 is kept, so
-- someone reading the database (or a backup) still can't cancel other people's bookings
ALTER TABLE booking
    ADD COLUMN cancel_token_hash TEXT,
    ADD COLUMN cancelled_at      TIMESTAMP,
    ADD COLUMN cancelled_by      TEXT CHECK (cancelled_by IN ('CLIENT', 'STAFF'));

CREATE UNIQUE INDEX booking_cancel_token ON booking (cancel_token_hash) WHERE cancel_token_hash IS NOT NULL;

-- Someone who booked and didn't come. Only marked on a booking whose time has passed; the
-- booking stays confirmed (the time was taken), it just didn't happen.
ALTER TABLE booking ADD COLUMN no_show BOOLEAN NOT NULL DEFAULT FALSE;

-- The price when it was booked, so the month's report doesn't change when a price goes up.
-- A trigger fills it in from the service, so every way of inserting a booking gets it.
ALTER TABLE booking ADD COLUMN price_cents BIGINT;
UPDATE booking b SET price_cents = s.price_cents FROM service s WHERE s.id = b.service_id;

CREATE FUNCTION booking_price() RETURNS trigger AS $$
BEGIN
    IF NEW.price_cents IS NULL THEN
        NEW.price_cents := (SELECT price_cents FROM service WHERE id = NEW.service_id);
    END IF;
    RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER booking_price BEFORE INSERT ON booking FOR EACH ROW EXECUTE FUNCTION booking_price();
ALTER TABLE booking ALTER COLUMN price_cents SET NOT NULL;

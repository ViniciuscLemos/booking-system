-- When the staff sent the reminder for a booking (by WhatsApp, from the agenda), so the
-- next person at the desk doesn't send it again. Moving the booking clears it.
ALTER TABLE booking ADD COLUMN reminded_at TIMESTAMP;

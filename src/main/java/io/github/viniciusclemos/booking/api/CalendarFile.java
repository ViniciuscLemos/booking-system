package io.github.viniciusclemos.booking.api;

import io.github.viniciusclemos.booking.store.BookingRow;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * A booking as an .ics file, the format every calendar app opens (Google, Apple, Outlook).
 * Times go in UTC so no app has to know the São Paulo timezone rules, and the UID stays
 * the same when the booking moves, so importing it again updates the event instead of
 * adding a second one.
 */
final class CalendarFile {

    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    private CalendarFile() {
    }

    static String of(BookingRow booking, String uid, String business, String address, ZoneId zone, Instant now) {
        String status = booking.confirmed() ? "CONFIRMED" : "CANCELLED";
        StringBuilder out = new StringBuilder();
        line(out, "BEGIN:VCALENDAR");
        line(out, "VERSION:2.0");
        line(out, "PRODID:-//booking-system//EN");
        line(out, "METHOD:PUBLISH");
        line(out, "BEGIN:VEVENT");
        line(out, "UID:" + uid);
        line(out, "DTSTAMP:" + UTC.format(now.atOffset(ZoneOffset.UTC)));
        line(out, "DTSTART:" + utc(booking.starts(), zone));
        line(out, "DTEND:" + utc(booking.ends(), zone));
        line(out, "SUMMARY:" + text(booking.serviceName() + " with " + booking.professionalName() + " at " + business));
        if (address != null && !address.isBlank()) line(out, "LOCATION:" + text(address));
        line(out, "STATUS:" + status);
        // a reminder the day before and one an hour before, which is what a phone actually shows
        alarm(out, "-P1D", "Tomorrow: " + booking.serviceName());
        alarm(out, "-PT1H", "In an hour: " + booking.serviceName());
        line(out, "END:VEVENT");
        line(out, "END:VCALENDAR");
        return out.toString();
    }

    private static void alarm(StringBuilder out, String before, String what) {
        line(out, "BEGIN:VALARM");
        line(out, "ACTION:DISPLAY");
        line(out, "TRIGGER:" + before);
        line(out, "DESCRIPTION:" + text(what));
        line(out, "END:VALARM");
    }

    private static String utc(LocalDateTime local, ZoneId zone) {
        return UTC.format(local.atZone(zone).withZoneSameInstant(ZoneOffset.UTC));
    }

    // commas, semicolons and backslashes mean something in the format, so they're escaped
    private static String text(String value) {
        return value.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n");
    }

    // the format wants CRLF and lines of at most 75 bytes, longer ones folded with a space
    private static void line(StringBuilder out, String line) {
        byte[] bytes = line.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length <= 75) {
            out.append(line).append("\r\n");
            return;
        }
        StringBuilder current = new StringBuilder();
        int size = 0;
        boolean first = true;
        for (int i = 0; i < line.length(); ) {
            int cp = line.codePointAt(i);
            int len = new String(Character.toChars(cp)).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            int limit = first ? 75 : 74;
            if (size + len > limit) {
                out.append(current).append("\r\n ");
                current.setLength(0);
                size = 0;
                first = false;
            }
            current.appendCodePoint(cp);
            size += len;
            i += Character.charCount(cp);
        }
        out.append(current).append("\r\n");
    }
}

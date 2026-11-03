package io.github.viniciusclemos.booking.auth;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * After 5 wrong passwords for the same e-mail in 15 minutes, that e-mail can't try
 * again until the oldest attempt is 15 minutes old. Kept in memory: a restart clears
 * it, which is fine for one server and saves a table.
 */
@Component
public class LoginAttempts {

    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);

    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginAttempts(Clock clock) {
        this.clock = clock;
    }

    /** How long until this e-mail can try again; zero when it can try now. */
    public Duration waitFor(String email) {
        Deque<Instant> recent = recent(key(email));
        if (recent == null || recent.size() < MAX_FAILURES) return Duration.ZERO;
        synchronized (recent) {
            return Duration.between(clock.instant(), recent.peekFirst().plus(WINDOW));
        }
    }

    public void failed(String email) {
        Deque<Instant> list = failures.computeIfAbsent(key(email), k -> new ArrayDeque<>());
        synchronized (list) {
            list.addLast(clock.instant());
            while (list.size() > MAX_FAILURES) list.removeFirst();
        }
    }

    public void succeeded(String email) {
        failures.remove(key(email));
    }

    private Deque<Instant> recent(String key) {
        Deque<Instant> list = failures.get(key);
        if (list == null) return null;
        synchronized (list) {
            Instant cutoff = clock.instant().minus(WINDOW);
            while (!list.isEmpty() && !list.peekFirst().isAfter(cutoff)) list.removeFirst();
        }
        return list;
    }

    private static String key(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}

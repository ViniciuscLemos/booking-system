package io.github.viniciusclemos.booking.scheduling;

import java.util.List;

public class BookingRefused extends RuntimeException {

    private final List<Violation> violations;

    public BookingRefused(List<Violation> violations) {
        super("booking refused: " + violations);
        this.violations = List.copyOf(violations);
    }

    public List<Violation> violations() {
        return violations;
    }
}

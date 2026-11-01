package io.github.viniciusclemos.booking.scheduling;

/** Why a time can't be booked. The message is what the client gets to read. */
public enum Violation {
    IN_THE_PAST("This time has already passed."),
    TOO_SOON("This time is too close, book a bit further ahead."),
    TOO_FAR_AHEAD("The calendar isn't open that far ahead yet."),
    CLOSED_THAT_DAY("Closed on this day."),
    OUTSIDE_WORKING_HOURS("Outside working hours."),
    CROSSES_BREAK("The appointment would run into a break."),
    OFF_GRID("Appointments start at fixed times, pick one of the listed ones."),
    TIME_OFF("The professional is away at this time."),
    CLASHES_WITH_BOOKING("Someone already booked this time.");

    private final String message;

    Violation(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}

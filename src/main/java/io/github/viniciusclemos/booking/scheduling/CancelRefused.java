package io.github.viniciusclemos.booking.scheduling;

/** A cancel that can't happen: too late, or already cancelled. The message goes to the client. */
public class CancelRefused extends RuntimeException {

    public CancelRefused(String message) {
        super(message);
    }
}

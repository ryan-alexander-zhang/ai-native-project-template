package com.aipersimmon.ddd.processmanager.exception;

/**
 * Thrown when a definition is handed an input it does not accept at the current
 * state — for example a {@code start} input for an already-running instance, or an
 * input a definition explicitly rejects. Out-of-order inputs a definition chooses to
 * ignore or compensate are not this exception; this is for inputs that are invalid,
 * not merely late.
 */
public final class UnsupportedProcessInputException extends ProcessException {

    public UnsupportedProcessInputException(String message) {
        super(message);
    }
}

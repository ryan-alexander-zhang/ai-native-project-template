package com.example.samples.s05.catalog.adapter;

/**
 * A message no number of retries will fix: unparseable, of a kind this consumer does not know, missing
 * something the translation requires, or refused by this context's own rules.
 *
 * <p>Having a single type for "permanent" is what lets the container's error handler make one decision
 * instead of guessing from a stack trace. The classification is made <em>at the boundary</em>, by the
 * code that knows what it was trying to translate — the error handler downstream only routes.
 */
class UntranslatableMessageException extends RuntimeException {

  UntranslatableMessageException(String message) {
    super(message);
  }

  UntranslatableMessageException(String message, Throwable cause) {
    super(message, cause);
  }
}

package com.aipersimmon.ddd.integration;

/**
 * Thrown when an inbound message cannot be turned into a well-formed {@link EventEnvelope} because
 * a required attribute is missing or unparseable — most importantly the CloudEvents {@code id} (the
 * inbox deduplication key) or {@code type} (the catalog lookup key), both of which the spec makes
 * mandatory. It is a permanent failure: redelivering the same record will lack the same attribute,
 * so retrying is pointless and fabricating a substitute (a random id) would silently defeat the
 * inbox. The message should be dead-lettered, not reprocessed.
 */
public class MalformedIntegrationEventException extends RuntimeException {

  public MalformedIntegrationEventException(String message) {
    super(message);
  }
}

package com.example.samples.s05.catalog.domain;

/**
 * What an upstream change did.
 *
 * <p>{@link #SUPERSEDED} is a <strong>successful</strong> outcome, and saying so in the type is the
 * point: a message that has been overtaken is not a failure, must not be retried, and must not be dead
 * lettered. Modelling it as an exception would have turned normal operation into an error rate.
 */
public enum ChangeOutcome {
  /** The product did not exist here yet. */
  MIRRORED,
  /** A newer revision replaced what was held. */
  UPDATED,
  /** The incoming revision was not newer — a duplicate, or a message that arrived late. */
  SUPERSEDED
}

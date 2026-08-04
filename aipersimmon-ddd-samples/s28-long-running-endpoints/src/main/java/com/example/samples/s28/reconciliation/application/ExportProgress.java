package com.example.samples.s28.reconciliation.application;

import java.time.Instant;
import java.util.OptionalLong;

/**
 * How far along, as last published.
 *
 * <p>{@code updatedAt} is here because it is the only field that lets a client tell "slow" from "stuck", and
 * that distinction is the whole reason a progress query exists. A percentage that has not moved in ten minutes
 * says something a percentage alone cannot.
 *
 * @param rowsDone rows written so far
 * @param rowsTotal the denominator, when one is known
 * @param updatedAt when this reading was published
 */
public record ExportProgress(long rowsDone, Long rowsTotal, Instant updatedAt) {

  public OptionalLong total() {
    return rowsTotal == null ? OptionalLong.empty() : OptionalLong.of(rowsTotal);
  }
}

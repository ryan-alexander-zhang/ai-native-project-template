package com.aipersimmon.ddd.operationlog.model;

import com.aipersimmon.ddd.core.annotation.ValueObject;

/**
 * The causal triple copied from the explicit dispatch context: this operation's own {@code
 * messageId}, the flow-stable {@code correlationId}, and the {@code causationId} of the message
 * that directly caused it. The component reads these; it never invents a trace id.
 */
@ValueObject
public record Causality(String messageId, String correlationId, String causationId) {

  /** Empty causality, for direct-API calls that have no dispatch context. */
  public static Causality none() {
    return new Causality(null, null, null);
  }
}

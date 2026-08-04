package com.example.samples.s09.ticketing.domain;

import com.aipersimmon.ddd.core.annotation.ValueObject;
import com.aipersimmon.ddd.core.model.Identifier;

/** An order's identity, and the flow's business key. One flow per order, by construction. */
@ValueObject
public record TicketOrderId(String value) implements Identifier {

  public TicketOrderId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("ticket order id must not be blank");
    }
  }
}

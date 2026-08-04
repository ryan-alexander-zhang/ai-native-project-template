package com.example.samples.s27.customer.api;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.IntegrationEvent;

/**
 * The address changed.
 *
 * <p>It carries the new address only, and <strong>not the old one</strong>. Carrying both is the obvious
 * design — a consumer that keyed anything on the address would want it — and it is the design that leaves the
 * old address in an outbox row, in a broker's retention window, and in every consumer's log, for ever. An
 * event that says what is true now needs one value; an event that says what changed needs two and creates a
 * second copy of the thing being replaced.
 *
 * <p>Where a before/after really is required, the place for it is the audit log, whose retention is written
 * for exactly that and whose contents nobody replicates. See §7 of the companion document.
 */
@EventType(name = "com.example.samples.customers.EmailChanged", version = 1, source = "/customers")
public record EmailChanged(String customerId, String email) implements IntegrationEvent {

  @Override
  public String subject() {
    return customerId;
  }
}

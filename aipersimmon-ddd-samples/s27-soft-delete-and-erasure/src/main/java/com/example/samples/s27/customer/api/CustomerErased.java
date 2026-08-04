package com.example.samples.s27.customer.api;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.IntegrationEvent;

/**
 * This customer's personal data was erased, and yours has to be too.
 *
 * <p>The only event here that carries nothing but an id, and necessarily so: there is nothing left to carry,
 * and an erasure notice that quoted the data it was about would be self-defeating. It is an <em>instruction</em>
 * rather than a description, which makes it the odd one out among this context's events and worth flagging as
 * a deliberate exception rather than a slip — the usual rule is that an event states a fact and lets the
 * consumer decide what to do about it.
 *
 * <p>{@code erasedAt} is included because a consumer needs to be able to tell a fresh instruction from a
 * redelivered one without keeping the message id for ever, and because their own audit trail will want to
 * record when the obligation arose rather than when they got round to it.
 */
@EventType(name = "com.example.samples.customers.CustomerErased", version = 1, source = "/customers")
public record CustomerErased(String customerId, String erasedAt) implements IntegrationEvent {

  @Override
  public String subject() {
    return customerId;
  }
}

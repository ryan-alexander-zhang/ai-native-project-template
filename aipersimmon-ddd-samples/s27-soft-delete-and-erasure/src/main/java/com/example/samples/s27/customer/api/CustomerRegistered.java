package com.example.samples.s27.customer.api;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.IntegrationEvent;

/**
 * A customer joined.
 *
 * <p>It carries the email and the name, which is the point: a consumer keeping its own customer replica needs
 * the values. It is therefore also the first place the personal data leaves this service, and every copy made
 * from it is outside the reach of anything this service can overwrite later.
 */
@EventType(name = "com.example.samples.customers.CustomerRegistered", version = 1, source = "/customers")
public record CustomerRegistered(String customerId, String email, String displayName)
    implements IntegrationEvent {

  @Override
  public String subject() {
    return customerId;
  }
}

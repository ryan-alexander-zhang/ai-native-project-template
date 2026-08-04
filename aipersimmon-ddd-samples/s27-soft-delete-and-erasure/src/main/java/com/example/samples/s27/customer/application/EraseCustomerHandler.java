package com.example.samples.s27.customer.application;

import com.aipersimmon.ddd.application.ApplicationException;
import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s27.customer.api.CustomerErased;
import com.example.samples.s27.customer.domain.Customer;
import com.example.samples.s27.customer.domain.CustomerErrorCode;
import com.example.samples.s27.customer.domain.CustomerId;
import com.example.samples.s27.customer.domain.Customers;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * The one command in this series that reads the outbox, and refuses when it is not empty.
 *
 * <p><strong>Why it has to.</strong> An unsent announcement about this customer contains their address. Once
 * the erasure has run there are only bad options for that row:
 *
 * <ul>
 *   <li><strong>Send it anyway.</strong> A fresh copy of the data, created after the moment it was supposed to
 *       be gone, delivered to every consumer.
 *   <li><strong>Delete it.</strong> The change it announced really happened, so every consumer is now
 *       permanently wrong about this customer, with nothing to detect it — the outbox exists precisely to make
 *       that impossible.
 *   <li><strong>Rewrite it.</strong> A published contract carrying values that were never true. Worse than
 *       either.
 * </ul>
 *
 * <p>So there is no correct action to take <em>after</em> the fact, which means the ordering has to be arranged
 * <em>before</em>: drain the queue, then erase. This handler enforces that by refusing, which turns an
 * unanswerable question into a retryable 409 — an erasure request is not a synchronous obligation, and "try
 * again once the queue is empty" is a complete answer to it.
 *
 * <p>It then publishes {@link CustomerErased}, because overwriting the local columns discharges the obligation
 * in exactly one database. Every consumer that kept a copy has the same duty and no way to know about it
 * otherwise.
 *
 * <p><strong>What it deliberately does not touch:</strong> the inbox (it holds message ids, not people — see
 * {@code ErasureAndInboxTest}) and the audit log (it is the evidence, and what it contains was decided when it
 * was written — see {@code ErasureAndAuditTest}).
 */
@Component
class EraseCustomerHandler implements CommandHandler<EraseCustomer, Void> {

  private final Customers customers;
  private final OutboxQueue outboxQueue;
  private final IntegrationEvents integrationEvents;
  private final MarketingConsents consents;

  EraseCustomerHandler(
      Customers customers,
      OutboxQueue outboxQueue,
      IntegrationEvents integrationEvents,
      MarketingConsents consents) {
    this.customers = customers;
    this.outboxQueue = outboxQueue;
    this.integrationEvents = integrationEvents;
    this.consents = consents;
  }

  @Override
  public Void handle(EraseCustomer command, CommandContext context) {
    CustomerId id = new CustomerId(command.customerId());
    Customer customer =
        customers
            .find(id)
            .orElseThrow(
                () ->
                    new EntityNotFoundException(
                        CustomerErrorCode.CUSTOMER_NOT_FOUND, "no customer " + command.customerId()));

    long queued = outboxQueue.unsentFor(id.value());
    if (queued > 0) {
      throw new ApplicationException(
          CustomerErrorCode.ANNOUNCEMENTS_STILL_QUEUED,
          queued
              + " announcement(s) about "
              + id.value()
              + " have not been delivered yet. Erasing now would either publish the personal data after"
              + " the moment it was to be erased, or drop a change every consumer already depends on."
              + " Let the relay drain and retry.");
    }

    Instant erasedAt = Instant.now();
    if (!customer.erase(erasedAt)) {
      // Already erased. Not an error: an erasure request arrives more than once, and the second one
      // finding nothing to do is the correct outcome. The original date is kept, because it is evidence.
      return null;
    }
    customers.save(customer);
    // The aggregate is not the only place personal data lives. A consent row names a person, so it goes —
    // and this one really is a delete, because unlike the customer row there is nothing about a consent
    // whose existence anybody has to be able to prove. Which is the general shape of the decision: keep the
    // row when its existence is evidence, delete it when only its contents were ever the point.
    consents.forget(id);
    integrationEvents.publish(
        new CustomerErased(id.value(), erasedAt.toString()), context);
    return null;
  }
}

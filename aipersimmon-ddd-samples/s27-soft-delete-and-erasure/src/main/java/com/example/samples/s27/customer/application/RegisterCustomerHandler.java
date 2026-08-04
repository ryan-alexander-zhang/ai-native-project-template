package com.example.samples.s27.customer.application;

import com.aipersimmon.ddd.application.DuplicateEntityException;
import com.aipersimmon.ddd.application.ApplicationException;
import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s27.customer.api.CustomerRegistered;
import com.example.samples.s27.customer.domain.Customer;
import com.example.samples.s27.customer.domain.CustomerErrorCode;
import com.example.samples.s27.customer.domain.CustomerId;
import com.example.samples.s27.customer.domain.Customers;
import com.example.samples.s27.customer.domain.EmailAddress;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * Register, and translate the one database constraint that is a business rule.
 *
 * <p>The unique index on {@code email} is enforcement, not decoration, so a violation of it has to arrive at
 * the caller as a domain refusal rather than a 500. Which is also where the logical-delete interaction shows
 * up in production: with the naive index of V1, a suppressed customer's address stays taken and this handler
 * refuses a legitimate re-registration with "already taken" — technically accurate, and unfixable by anyone
 * who does not know about the {@code deleted} column.
 */
@Component
class RegisterCustomerHandler implements CommandHandler<RegisterCustomer, Void> {

  private final Customers customers;
  private final IntegrationEvents integrationEvents;

  RegisterCustomerHandler(Customers customers, IntegrationEvents integrationEvents) {
    this.customers = customers;
    this.integrationEvents = integrationEvents;
  }

  @Override
  public Void handle(RegisterCustomer command, CommandContext context) {
    Customer customer =
        Customer.register(
            new CustomerId(command.customerId()),
            new EmailAddress(command.email()),
            command.displayName(),
            command.phone());
    try {
      customers.save(customer);
    } catch (DuplicateEntityException | DuplicateKeyException taken) {
      // Two different constraints reach here — the primary key and the unique email — and the library
      // translates only the first into DuplicateEntityException. Both mean "somebody already has this",
      // which is a 409 either way.
      throw new ApplicationException(
          CustomerErrorCode.EMAIL_ALREADY_TAKEN,
          "cannot register " + command.customerId() + ": the id or the email is already taken",
          taken);
    }
    integrationEvents.publish(
        new CustomerRegistered(
            customer.id().value(), customer.email().value(), customer.displayName()),
        context);
    return null;
  }
}

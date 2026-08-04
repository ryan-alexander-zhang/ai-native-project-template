package com.example.samples.s27.customer.application;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s27.customer.api.EmailChanged;
import com.example.samples.s27.customer.domain.Customer;
import com.example.samples.s27.customer.domain.CustomerErrorCode;
import com.example.samples.s27.customer.domain.CustomerId;
import com.example.samples.s27.customer.domain.Customers;
import com.example.samples.s27.customer.domain.EmailAddress;
import org.springframework.stereotype.Component;

/** Change it and announce it. */
@Component
class ChangeEmailHandler implements CommandHandler<ChangeEmail, Void> {

  private final Customers customers;
  private final IntegrationEvents integrationEvents;

  ChangeEmailHandler(Customers customers, IntegrationEvents integrationEvents) {
    this.customers = customers;
    this.integrationEvents = integrationEvents;
  }

  @Override
  public Void handle(ChangeEmail command, CommandContext context) {
    Customer customer = load(command.customerId());
    if (!customer.changeEmailTo(new EmailAddress(command.email()))) {
      return null;
    }
    customers.save(customer);
    integrationEvents.publish(
        new EmailChanged(customer.id().value(), customer.email().value()), context);
    return null;
  }

  private Customer load(String id) {
    return customers
        .find(new CustomerId(id))
        .orElseThrow(
            () ->
                new EntityNotFoundException(
                    CustomerErrorCode.CUSTOMER_NOT_FOUND,
                    "no customer "
                        + id
                        + " (note: a suppressed row answers the same way — the logical-delete filter is"
                        + " applied by the mapper, so the application cannot tell 'hidden' from 'never"
                        + " existed', which is what the switch means)"));
  }
}

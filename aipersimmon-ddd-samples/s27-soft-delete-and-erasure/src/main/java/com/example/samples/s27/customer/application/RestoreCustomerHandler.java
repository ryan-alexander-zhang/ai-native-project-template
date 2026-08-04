package com.example.samples.s27.customer.application;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s27.customer.domain.CustomerId;
import com.example.samples.s27.customer.domain.Customers;
import org.springframework.stereotype.Component;

/** One call to the port. */
@Component
class RestoreCustomerHandler implements CommandHandler<RestoreCustomer, Boolean> {

  private final Customers customers;

  RestoreCustomerHandler(Customers customers) {
    this.customers = customers;
  }

  @Override
  public Boolean handle(RestoreCustomer command, CommandContext context) {
    return customers.restore(new CustomerId(command.customerId()));
  }
}

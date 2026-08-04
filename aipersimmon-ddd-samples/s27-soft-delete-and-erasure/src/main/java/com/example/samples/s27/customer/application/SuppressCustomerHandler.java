package com.example.samples.s27.customer.application;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s27.customer.domain.CustomerId;
import com.example.samples.s27.customer.domain.Customers;
import org.springframework.stereotype.Component;

/**
 * One call to the port, and no aggregate is loaded.
 *
 * <p>That absence is the signature of an infrastructure operation. A handler that loaded the customer in order
 * to hide its row would be claiming the model has something to say about the matter, and the first consequence
 * would be somebody adding a rule that reads the flag.
 */
@Component
class SuppressCustomerHandler implements CommandHandler<SuppressCustomer, Boolean> {

  private final Customers customers;

  SuppressCustomerHandler(Customers customers) {
    this.customers = customers;
  }

  @Override
  public Boolean handle(SuppressCustomer command, CommandContext context) {
    return customers.suppress(new CustomerId(command.customerId()));
  }
}

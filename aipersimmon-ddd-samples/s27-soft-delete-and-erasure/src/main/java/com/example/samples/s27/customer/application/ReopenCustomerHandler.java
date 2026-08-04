package com.example.samples.s27.customer.application;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s27.customer.domain.Customer;
import com.example.samples.s27.customer.domain.CustomerErrorCode;
import com.example.samples.s27.customer.domain.CustomerId;
import com.example.samples.s27.customer.domain.Customers;
import org.springframework.stereotype.Component;

/** Reopen it — unless it was erased, in which case there is nothing to reopen. */
@Component
class ReopenCustomerHandler implements CommandHandler<ReopenCustomer, Void> {

  private final Customers customers;

  ReopenCustomerHandler(Customers customers) {
    this.customers = customers;
  }

  @Override
  public Void handle(ReopenCustomer command, CommandContext context) {
    Customer customer =
        customers
            .find(new CustomerId(command.customerId()))
            .orElseThrow(
                () ->
                    new EntityNotFoundException(
                        CustomerErrorCode.CUSTOMER_NOT_FOUND, "no customer " + command.customerId()));
    if (!customer.reopen()) {
      return null;
    }
    customers.save(customer);
    return null;
  }
}

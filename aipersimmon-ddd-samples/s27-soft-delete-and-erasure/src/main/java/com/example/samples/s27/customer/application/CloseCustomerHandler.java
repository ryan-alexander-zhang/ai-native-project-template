package com.example.samples.s27.customer.application;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s27.customer.domain.Customer;
import com.example.samples.s27.customer.domain.CustomerErrorCode;
import com.example.samples.s27.customer.domain.CustomerId;
import com.example.samples.s27.customer.domain.Customers;
import org.springframework.stereotype.Component;

/**
 * Close it.
 *
 * <p>No integration event, deliberately: this sample publishes registration, email changes and erasure, and
 * closing is left local so that the outbox contains only what an erasure has to reckon with. In a real service
 * a closure is very much something consumers want to hear about.
 */
@Component
class CloseCustomerHandler implements CommandHandler<CloseCustomer, Void> {

  private final Customers customers;

  CloseCustomerHandler(Customers customers) {
    this.customers = customers;
  }

  @Override
  public Void handle(CloseCustomer command, CommandContext context) {
    Customer customer =
        customers
            .find(new CustomerId(command.customerId()))
            .orElseThrow(
                () ->
                    new EntityNotFoundException(
                        CustomerErrorCode.CUSTOMER_NOT_FOUND, "no customer " + command.customerId()));
    if (!customer.close(command.reason())) {
      return null;
    }
    customers.save(customer);
    return null;
  }
}

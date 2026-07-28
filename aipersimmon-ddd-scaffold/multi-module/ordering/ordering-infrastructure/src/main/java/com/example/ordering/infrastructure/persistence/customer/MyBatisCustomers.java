package com.example.ordering.infrastructure.persistence.customer;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.persistence.mybatisplus.MybatisPlusAggregateRepository;
import com.example.ordering.domain.customer.Customer;
import com.example.ordering.domain.customer.CustomerId;
import com.example.ordering.domain.customer.Customers;
import com.example.ordering.domain.shared.Money;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * PostgreSQL-backed {@link Customers} over {@code ordering.customers}.
 *
 * <p>Writable since credit became enforceable. Placing an order commits credit and cancelling one
 * returns it, both inside the command's transaction alongside the order itself — the same
 * deliberate multi-aggregate transaction inventory uses for stock, and defensible for the same
 * reason: these two aggregates share a database, so an invariant spanning them can be held in one
 * unit of work instead of chased with a compensation flow.
 *
 * <p>The version check does the real work. Two concurrent placements load the same {@code
 * usedCredit}, both pass {@code reserveCredit} on that snapshot, and the second save matches zero
 * rows and surfaces as a 409 — which is what makes the limit a constraint rather than a comparison
 * (issue-00071).
 */
@Repository
public class MyBatisCustomers extends MybatisPlusAggregateRepository<Customer, CustomerDo>
    implements Customers {

  private final CustomerMapper customers;

  public MyBatisCustomers(CustomerMapper customers, DomainEvents domainEvents) {
    super(customers, domainEvents);
    this.customers = customers;
  }

  @Override
  public void save(Customer customer) {
    saveAggregate(customer);
  }

  @Override
  protected CustomerDo toRow(Customer customer) {
    CustomerDo row = new CustomerDo();
    row.setId(customer.id().value());
    row.setName(customer.name());
    row.setCreditMinor(customer.creditLimit().amountMinor());
    row.setCurrency(customer.creditLimit().currency());
    row.setUsedMinor(customer.usedCredit().amountMinor());
    return row;
  }

  @Override
  public Optional<Customer> findById(CustomerId id) {
    CustomerDo row = customers.selectById(id.value());
    if (row == null) {
      return Optional.empty();
    }
    String currency = row.getCurrency();
    return Optional.of(
        Customer.reconstitute(
            new CustomerId(row.getId()),
            row.getName(),
            Money.of(row.getCreditMinor(), currency),
            Money.of(row.getUsedMinor(), currency),
            row.getVersion()));
  }
}

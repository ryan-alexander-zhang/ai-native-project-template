package com.example.ordering.domain.customer;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.annotation.Identity;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;
import com.example.ordering.domain.shared.Money;

/** The Customer aggregate root. Owns the credit limit an order is checked against. */
@AggregateRoot
public class Customer extends AbstractAggregateRoot<CustomerId> {

    private final CustomerId id;
    private final String name;
    private final Money creditLimit;

    public Customer(CustomerId id, String name, Money creditLimit) {
        this.id = id;
        this.name = name;
        this.creditLimit = creditLimit;
    }

    /** Whether this customer's credit limit covers the given amount. */
    public boolean canAfford(Money amount) {
        return amount.lessThanOrEqual(creditLimit);
    }

    @Override
    @Identity
    public CustomerId id() {
        return id;
    }

    public String name() {
        return name;
    }
}

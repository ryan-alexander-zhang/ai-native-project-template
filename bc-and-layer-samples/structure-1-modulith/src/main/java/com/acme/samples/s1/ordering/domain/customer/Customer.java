package com.acme.samples.s1.ordering.domain.customer;

import com.acme.samples.s1.shared.Money;

/** Customer aggregate root (a different aggregate the Order checks by id). */
public record Customer(String id, String name, Money creditLimit) {
    public boolean canAfford(Money amount) {
        return amount.lessThanOrEqual(creditLimit);
    }
}

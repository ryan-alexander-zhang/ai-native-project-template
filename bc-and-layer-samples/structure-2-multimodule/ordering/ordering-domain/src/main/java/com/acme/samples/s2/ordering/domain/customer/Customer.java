package com.acme.samples.s2.ordering.domain.customer;

import com.acme.samples.s2.shared.Money;

/** Customer aggregate root (a separate aggregate the Order references by id). */
public record Customer(String id, String name, Money creditLimit) {
    public boolean canAfford(Money amount) {
        return amount.lessThanOrEqual(creditLimit);
    }
}

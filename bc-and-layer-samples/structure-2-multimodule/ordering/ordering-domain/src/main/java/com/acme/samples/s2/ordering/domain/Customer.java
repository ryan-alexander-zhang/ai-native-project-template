package com.acme.samples.s2.ordering.domain;

import com.acme.samples.s2.shared.Money;

public record Customer(String id, String name, Money creditLimit) {
    public boolean canAfford(Money amount) {
        return amount.lessThanOrEqual(creditLimit);
    }
}

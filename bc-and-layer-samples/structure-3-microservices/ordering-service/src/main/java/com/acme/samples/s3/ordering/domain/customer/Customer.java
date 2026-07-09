package com.acme.samples.s3.ordering.domain.customer;

import com.acme.samples.s3.ordering.domain.shared.Money;

public record Customer(String id, String name, Money creditLimit) {
    public boolean canAfford(Money amount) { return amount.lessThanOrEqual(creditLimit); }
}

package com.example.inventory.domain.stock;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.annotation.Identity;
import com.aipersimmon.ddd.core.exception.DomainException;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;

/** The Stock aggregate root: available quantity of one SKU, with a reservation rule. */
@AggregateRoot
public class Stock extends AbstractAggregateRoot<Sku> {

    private final Sku sku;
    private int available;

    public Stock(Sku sku, int available) {
        if (available < 0) {
            throw new DomainException("available must be >= 0");
        }
        this.sku = sku;
        this.available = available;
    }

    /** Reserve the given quantity, guarding against reserving more than is available. */
    public void reserve(int quantity) {
        if (quantity <= 0) {
            throw new DomainException("quantity must be > 0");
        }
        if (quantity > available) {
            throw new DomainException("insufficient stock for " + sku.value());
        }
        this.available -= quantity;
    }

    public int available() {
        return available;
    }

    @Override
    @Identity
    public Sku id() {
        return sku;
    }
}

package com.example.ordering.domain.order;

import com.aipersimmon.ddd.core.annotation.Entity;
import com.aipersimmon.ddd.core.exception.DomainException;
import com.example.ordering.domain.shared.Money;

/**
 * A line of an {@link Order}. Package-private on purpose: it is an internal entity
 * of the aggregate, so nothing outside this package can construct or reference it
 * — the only way in is through {@link Order}.
 */
@Entity
class OrderLine {

    private final String sku;
    private final int quantity;
    private final Money unitPrice;

    OrderLine(String sku, int quantity, Money unitPrice) {
        if (sku == null || sku.isBlank()) {
            throw new DomainException("sku required");
        }
        if (quantity <= 0) {
            throw new DomainException("quantity must be > 0");
        }
        this.sku = sku;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
    }

    String sku() {
        return sku;
    }

    Money subtotal() {
        return unitPrice.times(quantity);
    }
}

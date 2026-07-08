package com.acme.samples.s1.ordering.domain;

import com.acme.samples.s1.shared.Money;

/** Entity within the Order aggregate. */
public record OrderLine(String sku, int qty, long unitPriceMinor) {
    public OrderLine {
        if (qty <= 0) throw new IllegalArgumentException("qty must be > 0");
    }

    public Money lineTotal() {
        return Money.usd(unitPriceMinor * qty);
    }
}

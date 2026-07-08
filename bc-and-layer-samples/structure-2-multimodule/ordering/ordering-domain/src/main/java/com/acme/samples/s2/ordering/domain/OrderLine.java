package com.acme.samples.s2.ordering.domain;

import com.acme.samples.s2.shared.Money;

public record OrderLine(String sku, int qty, long unitPriceMinor) {
    public OrderLine {
        if (qty <= 0) throw new IllegalArgumentException("qty must be > 0");
    }

    public Money lineTotal() {
        return Money.usd(unitPriceMinor * qty);
    }
}

package com.acme.samples.s1.ordering.domain.order;

import com.acme.samples.s1.shared.Money;

/**
 * Internal entity of the Order aggregate. Deliberately <b>package-private</b>:
 * only {@link Order} (same package) may create or hold one. Outside this package
 * you cannot {@code new OrderLine(...)} or even name the type — the compiler
 * enforces "reach the aggregate only through its root". Data crosses the boundary
 * as {@link OrderLineData}.
 */
record OrderLine(String sku, int qty, long unitPriceMinor) {
    OrderLine {
        if (qty <= 0) throw new IllegalArgumentException("qty must be > 0");
    }

    Money lineTotal() {
        return Money.usd(unitPriceMinor * qty);
    }
}

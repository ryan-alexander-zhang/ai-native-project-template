package com.example.howto;

import com.aipersimmon.ddd.cqrs.Query;

/** Query for an order's read-model summary; answered from the read model, not the aggregate. */
public record FindOrderSummary(String orderId) implements Query<OrderSummary> {
}

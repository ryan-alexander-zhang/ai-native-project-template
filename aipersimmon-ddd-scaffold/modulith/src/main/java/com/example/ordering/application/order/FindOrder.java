package com.example.ordering.application.order;

import com.aipersimmon.ddd.cqrs.Query;
import java.util.Optional;

/** Query for an order's read-side snapshot; empty if there is no such order. */
public record FindOrder(String orderId) implements Query<Optional<OrderSnapshot>> {
}

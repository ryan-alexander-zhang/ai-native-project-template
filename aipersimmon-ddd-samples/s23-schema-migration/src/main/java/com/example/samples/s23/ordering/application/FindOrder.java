package com.example.samples.s23.ordering.application;

import com.aipersimmon.ddd.cqrs.Query;
import java.util.Optional;

/** Ask for one order. Empty when there is no such order. */
public record FindOrder(String orderId) implements Query<Optional<OrderView>> {}

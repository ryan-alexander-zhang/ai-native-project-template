package com.example.samples.s04.ordering.application;

import com.aipersimmon.ddd.cqrs.Query;
import java.util.Optional;

/** Ask for one order. Empty when there is none the caller may see. */
public record FindOrder(String orderId) implements Query<Optional<OrderView>> {}

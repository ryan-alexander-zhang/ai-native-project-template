package com.example.ordering.domain.order;

import com.example.ordering.domain.shared.Money;
import com.example.ordering.domain.shared.Sku;

/**
 * Raw line input used to build an {@link Order}'s internal lines. Passing data (not {@code
 * OrderLine} instances) lets the internal entity stay package-private while callers in other layers
 * still create orders through the root's factory.
 *
 * <p>"Raw" means free of the aggregate's internal entity, not free of the context's types: {@link
 * Sku} and {@link Money} are both value objects, so a caller cannot build a line out of two strings
 * in the wrong order. Turning a primitive into a {@code Sku} is the application layer's job, at the
 * point where a command's input becomes domain input.
 */
public record LineData(Sku sku, int quantity, Money unitPrice) {}

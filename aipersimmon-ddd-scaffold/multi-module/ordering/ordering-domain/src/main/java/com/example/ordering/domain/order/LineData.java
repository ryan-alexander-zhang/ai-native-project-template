package com.example.ordering.domain.order;

import com.example.ordering.domain.shared.Money;

/**
 * Raw line input used to build an {@link Order}'s internal lines. Passing data (not {@code
 * OrderLine} instances) lets the internal entity stay package-private while callers in other layers
 * still create orders through the root's factory.
 */
public record LineData(String sku, int quantity, Money unitPrice) {}

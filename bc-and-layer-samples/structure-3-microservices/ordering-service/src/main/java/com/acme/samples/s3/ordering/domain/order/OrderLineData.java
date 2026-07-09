package com.acme.samples.s3.ordering.domain.order;

/**
 * Public data carrier for an order line crossing the aggregate boundary. The
 * application supplies these to {@link Order#place}, and repositories read them
 * back from {@link Order#lines()} — so the internal {@link OrderLine} entity can
 * stay package-private.
 */
public record OrderLineData(String sku, int qty, long unitPriceMinor) {}

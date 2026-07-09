package com.acme.samples.s3.ordering.app.order;

/** Driven port: external pricing lookup (HTTP). */
public interface PricingPort {
    long unitPriceMinor(String sku);
}

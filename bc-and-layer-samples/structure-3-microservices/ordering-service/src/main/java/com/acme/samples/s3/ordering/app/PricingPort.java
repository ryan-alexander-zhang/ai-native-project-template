package com.acme.samples.s3.ordering.app;

/** Driven port: external pricing lookup (HTTP). */
public interface PricingPort {
    long unitPriceMinor(String sku);
}

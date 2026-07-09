package com.acme.samples.s1.ordering.application.order;

/** Driven port: fetch a SKU's unit price from an external pricing service. */
public interface PricingPort {
    long unitPriceMinor(String sku);
}

package com.example.howto;

import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;

/**
 * The write-side aggregate. Creating one records an {@link OrderPlaced} domain
 * event; the command's transaction interceptor drains and publishes it after the
 * handler registers the aggregate with the collector.
 */
public class Order extends AbstractAggregateRoot<String> {

    private final String id;
    private final String sku;

    public Order(String id, String sku) {
        this.id = id;
        this.sku = sku;
        registerEvent(new OrderPlaced(id, sku));
    }

    @Override
    public String id() {
        return id;
    }

    public String sku() {
        return sku;
    }
}

package com.acme.samples.s3.ordering.infrastructure;

import com.acme.samples.s3.ordering.app.InventoryPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Synchronous cross-service adapter: calls inventory-service over HTTP. */
@Component
public class InventoryClient implements InventoryPort {

    public record AvailabilityResponse(String sku, int qty, boolean available) {}

    private final RestClient restClient;

    public InventoryClient(@Value("${samples.inventory-base-url}") String baseUrl) {
        this.restClient = RestClient.create(baseUrl);
    }

    @Override
    public boolean isAvailable(String sku, int qty) {
        AvailabilityResponse r = restClient.get()
                .uri("/availability?sku={sku}&qty={qty}", sku, qty)
                .retrieve().body(AvailabilityResponse.class);
        return r != null && r.available();
    }
}

package com.acme.samples.s1.ordering.infrastructure;

import com.acme.samples.s1.ordering.application.PricingPort;
import com.acme.samples.s1.shared.SamplesProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Driven adapter: external HTTP call to the pricing service (WireMock). */
@Component
public class PricingClient implements PricingPort {

    public record PricingResponse(String sku, long unitPriceMinor, String currency, int taxRateBps) {}

    private final RestClient restClient;

    public PricingClient(SamplesProperties properties) {
        this.restClient = RestClient.create(properties.pricingBaseUrl());
    }

    @Override
    public long unitPriceMinor(String sku) {
        PricingResponse response = restClient.get()
                .uri("/pricing?sku={sku}", sku)
                .retrieve()
                .body(PricingResponse.class);
        if (response == null) throw new IllegalStateException("no pricing for sku " + sku);
        return response.unitPriceMinor();
    }
}

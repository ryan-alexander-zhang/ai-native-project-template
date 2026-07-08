package com.acme.samples.s2.ordering.infrastructure;

import com.acme.samples.s2.ordering.application.PricingPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Driven adapter: external HTTP pricing call (WireMock). */
@Component
public class PricingClient implements PricingPort {

    public record PricingResponse(String sku, long unitPriceMinor, String currency, int taxRateBps) {}

    private final RestClient restClient;

    public PricingClient(@Value("${samples.pricing-base-url}") String baseUrl) {
        this.restClient = RestClient.create(baseUrl);
    }

    @Override
    public long unitPriceMinor(String sku) {
        PricingResponse response = restClient.get().uri("/pricing?sku={sku}", sku)
                .retrieve().body(PricingResponse.class);
        if (response == null) throw new IllegalStateException("no pricing for sku " + sku);
        return response.unitPriceMinor();
    }
}

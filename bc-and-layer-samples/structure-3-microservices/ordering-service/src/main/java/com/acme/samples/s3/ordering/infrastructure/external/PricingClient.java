package com.acme.samples.s3.ordering.infrastructure;

import com.acme.samples.s3.ordering.app.PricingPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** External HTTP pricing call (WireMock). */
@Component
public class PricingClient implements PricingPort {

    public record PricingResponse(String sku, long unitPriceMinor, String currency, int taxRateBps) {}

    private final RestClient restClient;

    public PricingClient(@Value("${samples.pricing-base-url}") String baseUrl) {
        this.restClient = RestClient.create(baseUrl);
    }

    @Override
    public long unitPriceMinor(String sku) {
        PricingResponse r = restClient.get().uri("/pricing?sku={sku}", sku).retrieve().body(PricingResponse.class);
        if (r == null) throw new IllegalStateException("no pricing for sku " + sku);
        return r.unitPriceMinor();
    }
}

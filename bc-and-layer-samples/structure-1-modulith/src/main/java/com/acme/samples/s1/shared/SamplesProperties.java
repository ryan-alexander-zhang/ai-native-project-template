package com.acme.samples.s1.shared;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Config reading: bound from the {@code samples.*} tree in application.yml. */
@ConfigurationProperties("samples")
public record SamplesProperties(String pricingBaseUrl, String orderPlacedTopic, String stockResultTopic) {
}

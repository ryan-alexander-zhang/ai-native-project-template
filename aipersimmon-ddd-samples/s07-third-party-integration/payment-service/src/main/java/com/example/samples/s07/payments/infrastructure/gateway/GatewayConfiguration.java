package com.example.samples.s07.payments.infrastructure.gateway;

import com.aipersimmon.ddd.integration.IntegrationEventCatalog;
import com.aipersimmon.ddd.outbox.OutboxDispatcher;
import com.aipersimmon.ddd.outbox.spring.InProcessOutboxDispatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** Assembly for everything that faces the provider. */
@Configuration(proxyBeanMethods = false)
class GatewayConfiguration {

  /**
   * The client, and the two numbers that decide what a bad day at the provider costs us.
   *
   * <p>Spring's default request factory has <strong>no read timeout</strong>, so a provider that accepts
   * a connection and then goes quiet would hold the relay's thread indefinitely — and the relay is a
   * small pool, so one hung request stops every other payment from being sent. The connect timeout is
   * short because a connection either happens quickly or is not going to; the read timeout is longer
   * because taking money legitimately takes a moment.
   *
   * <p>Both are far shorter than the outbox's retry backoff, which is the right shape: fail the attempt
   * quickly, then wait a while before the next one. A long timeout and a short backoff pile attempts on
   * top of a struggling provider, which is how a slow dependency becomes a dead one.
   */
  @Bean
  RestClient gatewayClient(
      RestClient.Builder builder,
      @Value("${payments.gateway.base-url}") String baseUrl,
      @Value("${payments.gateway.connect-timeout-ms:1000}") long connectTimeoutMs,
      @Value("${payments.gateway.read-timeout-ms:3000}") long readTimeoutMs) {
    ClientHttpRequestFactorySettings settings =
        ClientHttpRequestFactorySettings.defaults()
            .withConnectTimeout(Duration.ofMillis(connectTimeoutMs))
            .withReadTimeout(Duration.ofMillis(readTimeoutMs));
    return builder
        .baseUrl(baseUrl)
        .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
        .build();
  }

  /**
   * The application's single {@link OutboxDispatcher}, assembled from two legs.
   *
   * <p>The in-process leg is constructed here rather than injected, because the library's own bean for it
   * is {@code @ConditionalOnMissingBean(OutboxDispatcher.class)} — it has already backed off by the time
   * this bean exists, and asking for it by type would be asking for this bean. Building it takes the three
   * collaborators below and is the price of routing by destination.
   */
  @Bean
  OutboxDispatcher chargeRequestOutboxDispatcher(
      RestClient gatewayClient,
      ObjectMapper objectMapper,
      ApplicationEventPublisher publisher,
      IntegrationEventCatalog catalog,
      @Value("${payments.gateway.destination:gateway:charges}") String destination) {
    return new ChargeRequestOutboxDispatcher(
        new InProcessOutboxDispatcher(publisher, objectMapper, catalog),
        gatewayClient,
        objectMapper,
        destination);
  }
}

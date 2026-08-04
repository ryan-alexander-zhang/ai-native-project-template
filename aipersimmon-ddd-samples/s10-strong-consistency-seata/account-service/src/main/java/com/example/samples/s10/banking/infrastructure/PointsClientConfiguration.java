package com.example.samples.s10.banking.infrastructure;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * The client for the points service, with timeouts that matter more here than in S6.
 *
 * <p>A read timeout inside a global transaction is not only a request thread: it is a request thread, a
 * database connection, <em>and</em> a global lock on the account row — so every other global transaction
 * touching that account queues behind this one until the timeout fires. The read timeout is therefore an
 * upper bound on how long one slow participant can block everyone who shares a row with it, and the global
 * transaction timeout has to be larger than it or the coordinator will start rolling back branches that are
 * still working.
 */
@Configuration(proxyBeanMethods = false)
class PointsClientConfiguration {

  @Bean
  RestClient pointsClient(
      RestClient.Builder builder,
      @Value("${banking.points-service-url}") String baseUrl,
      @Value("${banking.points-connect-timeout-ms:1000}") long connectTimeoutMs,
      @Value("${banking.points-read-timeout-ms:3000}") long readTimeoutMs) {
    ClientHttpRequestFactorySettings settings =
        ClientHttpRequestFactorySettings.defaults()
            .withConnectTimeout(Duration.ofMillis(connectTimeoutMs))
            .withReadTimeout(Duration.ofMillis(readTimeoutMs));
    return builder
        .baseUrl(baseUrl)
        .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
        .build();
  }
}

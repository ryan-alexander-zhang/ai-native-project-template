package com.example.samples.s06.ordering.infrastructure;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * The client, and the two numbers that decide what happens on a bad day.
 *
 * <p><strong>A timeout is not optional.</strong> Spring's default request factory has no read timeout at
 * all, so a callee that accepts a connection and then stops responding parks this thread until the
 * platform gives up — which for a synchronous dependency is how one slow service takes down its callers
 * one thread at a time. Two seconds here is a sample's number; the real one is "shorter than the caller's
 * own SLA, and short enough that the thread pool survives the callee being slow rather than down".
 *
 * <p>The timeout is also what makes the precheck's placement matter: with the call outside the
 * transaction, two seconds of waiting costs a request thread. Inside it, it would also cost a database
 * connection for the same two seconds.
 */
@Configuration(proxyBeanMethods = false)
class RiskClientConfiguration {

  @Bean
  RestClient riskClient(
      RestClient.Builder builder,
      @Value("${ordering.risk-service-url}") String baseUrl,
      @Value("${ordering.risk-connect-timeout-ms:1000}") long connectTimeoutMs,
      @Value("${ordering.risk-read-timeout-ms:2000}") long readTimeoutMs) {
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

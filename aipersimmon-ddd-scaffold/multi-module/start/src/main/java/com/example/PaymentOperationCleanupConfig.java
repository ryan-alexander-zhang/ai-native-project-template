package com.example;

import com.example.payment.infrastructure.PaymentOperationCleanup;
import com.example.payment.infrastructure.PaymentOperationMapper;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The retention decision for {@code payment_operations}.
 *
 * <p>It lives here rather than beside the adapter for the same reason the framework keeps its own
 * outbox and inbox cleanups behind opt-in configuration: whether to delete data, and after how
 * long, is a property of a deployment, not of a persistence adapter. The composition root is where
 * that kind of question gets answered.
 *
 * <p>The question itself was missed once already, and how is the interesting part. The log used to
 * be a {@code ConcurrentHashMap}, which had a retention policy — the process restarts and it is
 * empty — that nobody had written down, because it was not a choice, it was a side effect. Making
 * the log durable was argued carefully and correctly on one property, co-transactionality ; the
 * other properties that changed with it, lifetime among them, went unexamined. An unstated policy
 * leaves no trace when it is removed.
 *
 * <p>The window matches the inbox's thirty days, and not by coincidence — see {@code
 * application.yml}, where the reasoning is set out next to the inbox's own. Both tables prevent a
 * duplicate from being processed twice, so both must outlive the longest redelivery the broker can
 * produce.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "payment.operations.cleanup",
    name = "enabled",
    havingValue = "true")
public class PaymentOperationCleanupConfig {

  /**
   * Takes the application's {@link Clock} bean rather than constructing {@code Clock.systemUTC()}
   * here: the cutoff this computes closes the same window {@code recorded_at} opens, so both must
   * read the same clock — the injected one every other time-dependent bean uses, and the one a test
   * can freeze.
   */
  @Bean
  PaymentOperationCleanup paymentOperationCleanup(
      PaymentOperationMapper operations,
      Clock clock,
      @Value("${payment.operations.cleanup.retention-seconds}") long retentionSeconds) {
    return new PaymentOperationCleanup(operations, clock, retentionSeconds);
  }
}

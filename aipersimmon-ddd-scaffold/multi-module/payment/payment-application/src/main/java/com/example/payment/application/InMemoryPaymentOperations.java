package com.example.payment.application;

import com.example.payment.domain.PaymentDecision;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * In-memory {@link PaymentOperations} — the scaffold's stand-in for a durable operations table. It
 * claims each {@code paymentOperationId} atomically via {@link Map#putIfAbsent}, so under
 * concurrent or repeated redelivery exactly one caller wins the claim and performs the charge.
 *
 * <p>Deliberately the lightest honest dedupe: the demo has no payment datastore, so keeping the
 * operation log in a process-local map is enough to prove the at-most-once property in a test. A
 * real deployment swaps this for a persistent, uniqueness-enforcing store (a {@code
 * payment_operations} table or the framework inbox) without touching {@link ChargePaymentHandler},
 * which depends only on the {@link PaymentOperations} port.
 */
@Component
public class InMemoryPaymentOperations implements PaymentOperations {

  private final Map<String, PaymentDecision> byOperationId = new ConcurrentHashMap<>();

  @Override
  public boolean recordIfFirst(String operationId, PaymentDecision decision) {
    return byOperationId.putIfAbsent(operationId, decision) == null;
  }

  @Override
  public Optional<PaymentDecision> find(String operationId) {
    return Optional.ofNullable(byOperationId.get(operationId));
  }
}

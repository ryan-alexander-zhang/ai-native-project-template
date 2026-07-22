package com.example.payment.application;

import com.example.payment.domain.PaymentDecision;
import java.util.Optional;

/**
 * The payment context's business-idempotency store, keyed by {@code paymentOperationId}. It is what
 * makes authorising an operation <em>at-most-once</em> under at-least-once delivery: the design
 * requires an irreversible action (a payment authorization) to dedupe on its own business operation
 * id, complementing — not replacing — the transport-level effect/event id (design-00004 §13.2,
 * :283-285).
 *
 * <p>{@link #recordIfFirst} is the atomic claim: it records the decision for a first-seen operation
 * and reports whether this caller won the claim. A redelivered authorization for an operation
 * already recorded loses the claim and must not authorise again; {@link #find} exposes the prior
 * outcome.
 *
 * <p>This is a port. The reference implementation ({@code InMemoryPaymentOperations}) keeps the log
 * in memory — the lightest honest dedupe for a scaffold with no payment datastore; a real
 * deployment backs it with a durable, uniqueness-enforcing operations table (or the framework
 * inbox).
 */
public interface PaymentOperations {

  /**
   * Atomically record {@code decision} for {@code operationId} iff no decision was recorded yet.
   *
   * @return {@code true} if this call recorded it (a first-seen operation — the caller should
   *     perform the authorization), {@code false} if the operation was already recorded (a
   *     redelivery — the caller must not authorise again)
   */
  boolean recordIfFirst(String operationId, PaymentDecision decision);

  /** The decision recorded for {@code operationId}, if the operation has been processed. */
  Optional<PaymentDecision> find(String operationId);
}

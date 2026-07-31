package com.example.payment.application;

import com.example.payment.domain.PaymentDecision;
import java.util.Optional;

/**
 * The payment context's business-idempotency log, keyed by {@code paymentOperationId}. It is what
 * makes authorising an operation <em>exactly-once</em> under at-least-once delivery: the design
 * requires an irreversible action (a payment authorization) to dedupe on its own business operation
 * id, complementing — not replacing — the transport-level effect/event id (design-00004 §13.2,
 * :283-285).
 *
 * <h2>The property that makes this pattern correct</h2>
 *
 * <p>It is <strong>not</strong> "the log is durable". It is that <em>recording the decision and
 * publishing its outcome commit or roll back together</em>. Durability is one necessary condition
 * for that, not the point of it: an ordinary database table written in its own transaction has
 * exactly the same hole a {@code ConcurrentHashMap} had. If the record survives a rolled-back
 * publish, every later redelivery sees an operation already handled and the outcome event is lost
 * for good (issue-00069).
 *
 * <p>So an implementation must write through the <em>caller's</em> transaction. The shipped one
 * does: a table on the same {@code DataSource} as the outbox, so a decision and the event
 * announcing it are one commit.
 *
 * <h2>Two operations, not one claim</h2>
 *
 * <p>{@link #find} first, {@link #record} only if nothing was found — deliberately not a single
 * {@code recordIfFirst} returning a boolean. The boolean form can only answer "may I proceed?",
 * which pushes the caller towards returning silently on a redelivery; and silence is the wrong
 * answer, because the premise of at-least-once delivery is that the previous outcome <em>may never
 * have arrived</em>. Splitting the two lets the handler take the recorded decision and republish
 * it, so a redelivery re-announces instead of swallowing.
 *
 * <p>Concurrency is resolved by {@link #record} failing rather than by a check: two simultaneous
 * first deliveries both find nothing, both decide, and the loser's insert violates the primary key.
 * That rolls its transaction back, and its retry finds the winner's decision — the same path a
 * redelivery takes. The uniqueness constraint is the claim.
 */
public interface PaymentOperations {

  /** The decision recorded for {@code operationId}, if this operation was already authorised. */
  Optional<PaymentDecision> find(String operationId);

  /**
   * Record {@code decision} as the outcome of {@code operationId}, in the caller's transaction.
   *
   * <p>Fails if the operation is already recorded. That failure is not exceptional — it is how two
   * concurrent first deliveries are resolved — and the caller is expected to let it roll the
   * transaction back so the redelivery can republish the winner's decision.
   */
  void record(String operationId, PaymentDecision decision);

  /**
   * Turn an {@code Authorized} outcome into {@code Voided}, in the caller's transaction — the one
   * sanctioned rewrite of a recorded decision (issue-00144): releasing a hold is undoing the
   * irreversible act's reservation, not re-deciding it. A no-op unless the current outcome is
   * {@code Authorized}, so a redelivered void, a void racing a decline, or a void of an operation
   * something else already voided all fall through harmlessly.
   *
   * <p>Voiding an operation nothing has recorded yet is {@code record(id, Voided)} — the ordinary
   * claim, resolved by the same primary key when it races the authorization's own insert.
   */
  void markVoided(String operationId);
}

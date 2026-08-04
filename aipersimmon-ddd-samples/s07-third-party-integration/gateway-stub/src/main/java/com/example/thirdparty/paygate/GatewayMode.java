package com.example.thirdparty.paygate;

/**
 * How this gateway misbehaves next. Every mode is something a real provider does, and each one is
 * the reason a specific piece of the payment service exists.
 */
public enum GatewayMode {

  /** Accepts, notifies acceptance, then notifies success. The path everything else deviates from. */
  NORMAL,

  /**
   * Charges successfully and never calls back. The most common real failure, and the one that cannot
   * be fixed by retrying anything: the answer exists on the gateway's side and no amount of waiting
   * brings it here. Only a pull channel resolves it — and it resolves it as a success, which is why
   * "no callback" must never be read as "no charge".
   */
  SILENT,

  /**
   * Accepts, calls back never, and stays pending forever when asked. The state that has no answer
   * yet rather than an answer we missed; the honest resolution is a human, not a guess.
   */
  SILENT_PENDING,

  /**
   * Accepts the charge request with a 202 and then has no record of it. A load balancer that
   * acknowledged and dropped, a provider incident, a request that never made it past the edge — the
   * caller cannot tell which, and "the gateway does not know this payment" is a different situation
   * from "the gateway has not decided yet".
   */
  FORGET_CHARGE,

  /**
   * Notifies the same outcome twice, with a fresh event id, nonce and signature each time.
   *
   * <p>Not a replay: every byte differs, both requests are authentic, and the replay guard is right
   * to accept both. Deduplicating this is business logic, and no edge filter can do it.
   */
  DUPLICATE_CALLBACK,

  /**
   * Notifies success first and acceptance second. Two callbacks racing through a load balancer is
   * enough; nothing about a webhook delivery is ordered.
   */
  REVERSED_CALLBACKS,

  /** Accepts, then declines with an insufficient-funds code. A business "no", not a fault. */
  DECLINE,

  /**
   * Notifies a result code this consumer has never heard of. Providers add codes without asking, and
   * the wrong reaction — treating unknown as failure — is how a successful charge is recorded as a
   * failed one.
   */
  UNKNOWN_RESULT_CODE,

  /**
   * Fails the first charge request with a 503 <em>before</em> creating anything, and behaves normally
   * afterwards. Drives the outbound retry. Note what it does <strong>not</strong> test: nothing was
   * charged, so a retry with a fresh key would also charge exactly once — see
   * {@link #LOSE_FIRST_RESPONSE} for the case where the key is what saves you.
   */
  FAIL_FIRST_CHARGE,

  /**
   * Creates the charge and <em>then</em> fails the response with a 503.
   *
   * <p>The failure mode that makes an idempotency key load-bearing rather than decorative, and the reason
   * "we got an error, so nothing happened" is never a safe assumption about a remote call: the money moved
   * and the caller has no way to know. A retry that reuses the key is answered from the first charge; a
   * retry that mints a fresh one debits the customer twice.
   */
  LOSE_FIRST_RESPONSE,

  /**
   * Charges normally but fails every status query with a 503. The pull channel is a dependency too, and
   * a reconciler that mistook "I could not ask" for "there is nothing there" would escalate every
   * payment in the backlog the first time the provider had a bad minute.
   */
  STATUS_QUERY_FAILS,

  /**
   * Refuses every charge request with a 400. The provider is saying "your request is wrong", and no
   * number of retries makes it right — so this is the case that has to leave the retry loop early
   * rather than burn ten attempts and an hour of backoff first.
   */
  REFUSE_CHARGE_REQUEST,

  /**
   * Notifies success and then, later, failure for the same charge. Should be impossible; happens.
   * There is no correct automatic resolution, which is the point of the test that covers it.
   */
  CONTRADICTORY_CALLBACKS
}

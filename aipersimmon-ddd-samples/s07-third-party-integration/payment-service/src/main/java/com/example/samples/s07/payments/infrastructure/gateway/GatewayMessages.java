package com.example.samples.s07.payments.infrastructure.gateway;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The provider's wire shapes, all four of them, in one place and package-private.
 *
 * <p>Package-private is the enforcement. Nothing outside this package can name these types, so "the
 * foreign model does not leak inward" is checked by the compiler on every build rather than by a
 * reviewer's memory. An ArchUnit rule can say the same thing, but only about the classes it was told to
 * look at.
 *
 * <p>{@code @JsonProperty} on every field, and {@code @JsonIgnoreProperties(ignoreUnknown = true)} on
 * every inbound record. The first is because the names are the provider's, not ours, and a global naming
 * strategy would make our own API speak snake case to match somebody else's taste. The second is because
 * a provider adds fields without telling anyone, and a webhook that starts rejecting deliveries because
 * of a field it does not care about is an outage we chose.
 */
final class GatewayMessages {

  private GatewayMessages() {}

  /** What we send to {@code POST /charges}. The idempotency key travels as a header, not here. */
  record ChargeRequest(
      @JsonProperty("merchant_ref") String merchantRef,
      @JsonProperty("amount_minor") long amountMinor,
      @JsonProperty("currency") String currency) {}

  /**
   * What {@code POST /charges} answers. Read, logged, and then deliberately dropped: see
   * {@link ChargeRequestOutboxDispatcher} for why the relay may not act on it.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record ChargeAccepted(@JsonProperty("txn_ref") String txnRef) {}

  /** What {@code GET /charges/{merchant_ref}} answers. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record ChargeStatus(
      @JsonProperty("txn_ref") String txnRef,
      @JsonProperty("merchant_ref") String merchantRef,
      @JsonProperty("result_code") String resultCode,
      @JsonProperty("result_desc") String resultDesc) {}

  /**
   * What arrives at the callback endpoint.
   *
   * <p>{@code event_id} is the provider's id for the notification, and it is worth noticing what it is
   * <em>not</em> good for: deduplication. The provider mints a fresh one for every send, including for
   * two sends of the same outcome, so two deliveries of "charge approved" carry different event ids.
   * Deduplicating on it would keep both. What actually dedupes here is the aggregate's state.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record ChargeNotification(
      @JsonProperty("event_id") String eventId,
      @JsonProperty("txn_ref") String txnRef,
      @JsonProperty("merchant_ref") String merchantRef,
      @JsonProperty("result_code") String resultCode,
      @JsonProperty("result_desc") String resultDesc,
      @JsonProperty("notified_at") String notifiedAt) {}
}

package com.example.samples.s07.payments.application;

/**
 * The pull channel: ask the provider where a charge stands.
 *
 * <p>The catalogue calls this mandatory and it is. A webhook is a best-effort push from a system that
 * has no idea whether we were up, so a payment whose callback is lost stays unsettled forever unless
 * something asks. Every provider offers this endpoint; the reason integrations skip it is that
 * everything works in testing, where the callback always arrives.
 *
 * <p>Note the direction and the shape. It is a <em>query</em>, which is what makes the reconciler safe to
 * run on every instance with no lease: repeating a question costs a request. The moment a reconciler
 * starts re-<em>sending</em> charges, that changes — and then the idempotency key is the only thing
 * standing between a retry and a second debit.
 */
public interface GatewayCharges {

  /**
   * Where this payment stands at the provider, right now.
   *
   * @param paymentId our id, which is also the reference the provider knows it by — the reason the pull
   *     channel is possible at all. A provider-minted reference we only learn from the callback would be
   *     unavailable in exactly the case where we need it: the callback that never came.
   */
  GatewayReport reportFor(String paymentId);
}

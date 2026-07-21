package com.aipersimmon.ddd.web.spi;

/**
 * Verifies the signature of an inbound request — the stateless half of replay protection, shared by
 * signed-request and webhook flows (e.g. Slack- or Stripe-style HMAC signatures).
 * Timestamp-tolerance and nonce checks are applied separately by the filter and {@link
 * ReplayGuard}.
 */
public interface RequestSignatureVerifier {

  /**
   * @param request the signed material to check
   * @return {@code true} if the signature is valid, {@code false} otherwise
   */
  boolean verify(SignedRequest request);
}

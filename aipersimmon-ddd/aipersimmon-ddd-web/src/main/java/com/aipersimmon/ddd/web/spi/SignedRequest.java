package com.aipersimmon.ddd.web.spi;

import java.time.Instant;

/**
 * The signed material a {@link RequestSignatureVerifier} checks: the presented signature, the body
 * it was computed over, the request timestamp, and an optional nonce. The timestamp is compared
 * against a tolerance window by the filter; the nonce, when present, is deduplicated by a {@link
 * ReplayGuard}.
 *
 * @param signature the presented signature value (e.g. an HMAC hex/base64 string)
 * @param body the payload the signature was computed over
 * @param timestamp the request timestamp
 * @param nonce a single-use nonce, or null if the scheme uses none
 */
public record SignedRequest(String signature, String body, Instant timestamp, String nonce) {

  public SignedRequest {
    if (signature == null || signature.isBlank()) {
      throw new IllegalArgumentException("signature must not be blank");
    }
    if (timestamp == null) {
      throw new IllegalArgumentException("timestamp must not be null");
    }
    body = body == null ? "" : body;
  }
}

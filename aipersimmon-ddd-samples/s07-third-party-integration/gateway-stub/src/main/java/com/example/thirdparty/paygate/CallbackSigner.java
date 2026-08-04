package com.example.thirdparty.paygate;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The signing half of the callback contract, and deliberately the only copy of it.
 *
 * <p>A webhook signature is a scheme, not a standard: the sender decides what string is signed, and
 * every provider decides differently. So the canonical form lives here, on the sending side, and the
 * consumer's verifier mirrors it. Publishing this class (as a real provider publishes an SDK or a
 * documentation page) is what makes the scheme knowable; guessing it from the receiving end is how
 * integrations end up with a verifier that accepts more than it should.
 *
 * <p>The canonical string is {@code <epochSeconds>.<nonce>.<body>}, HMAC-SHA256, lowercase hex.
 * Binding the timestamp and the nonce into the signed material — rather than signing the body alone
 * — is the part that matters: a signature over the body only can be replayed forever with a fresh
 * timestamp header, because the timestamp was never covered by it.
 */
public final class CallbackSigner {

  private static final String ALGORITHM = "HmacSHA256";

  private CallbackSigner() {}

  /** The string this scheme signs, given the three parts a receiver can also see. */
  public static String canonicalForm(long epochSeconds, String nonce, String body) {
    return epochSeconds + "." + (nonce == null ? "" : nonce) + "." + body;
  }

  /** The hex HMAC-SHA256 of {@link #canonicalForm} under {@code secret}. */
  public static String sign(long epochSeconds, String nonce, String body, String secret) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
      return HexFormat.of()
          .formatHex(
              mac.doFinal(canonicalForm(epochSeconds, nonce, body).getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 must be available", e);
    }
  }
}

package com.example.samples.s02.ordering.interfaces;

import com.aipersimmon.ddd.web.spi.RequestSignatureVerifier;
import com.aipersimmon.ddd.web.spi.SignedRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The one bean the library has no default for, and the reason to read this class before enabling
 * replay protection: {@code ReplayProtectionFilter} is registered only when a
 * {@code RequestSignatureVerifier} bean exists. Turn the property on without this and the
 * application starts, logs nothing, and accepts every unsigned request.
 *
 * <p>The library also defines no canonical form: it hands over the raw body, the parsed timestamp and
 * the nonce, and the algorithm is entirely this class's decision. The one below —
 * {@code &lt;epochSeconds&gt;.&lt;nonce&gt;.&lt;body&gt;} under HMAC-SHA256, hex — is this sample's contract, and any
 * client must mirror it exactly. Binding the timestamp and nonce into the signed string is what stops
 * an attacker replaying a captured body with a fresh timestamp.
 */
@Component
class HmacRequestSignatureVerifier implements RequestSignatureVerifier {

  private static final String ALGORITHM = "HmacSHA256";

  private final byte[] secret;

  HmacRequestSignatureVerifier(@Value("${samples.s02.webhook-secret}") String secret) {
    this.secret = secret.getBytes(StandardCharsets.UTF_8);
  }

  @Override
  public boolean verify(SignedRequest request) {
    String canonical =
        request.timestamp().getEpochSecond()
            + "."
            + (request.nonce() == null ? "" : request.nonce())
            + "."
            + request.body();
    // Constant-time comparison: a byte-by-byte early exit leaks how much of a guess was right.
    return MessageDigest.isEqual(
        sign(canonical, secret).getBytes(StandardCharsets.UTF_8),
        request.signature().getBytes(StandardCharsets.UTF_8));
  }

  /** Also used by the tests, which have to sign exactly the way this verifies. */
  static String sign(String canonical, byte[] secret) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(secret, ALGORITHM));
      return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 must be available", e);
    }
  }
}

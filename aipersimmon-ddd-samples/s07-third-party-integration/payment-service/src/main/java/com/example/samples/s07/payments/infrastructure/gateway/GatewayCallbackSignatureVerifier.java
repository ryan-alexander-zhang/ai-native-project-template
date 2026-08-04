package com.example.samples.s07.payments.infrastructure.gateway;

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
 * The provider's signing scheme, implemented on the receiving side.
 *
 * <p>The library has no default for this bean and cannot have one: there is no standard. It hands over the
 * raw body, the parsed timestamp and the nonce, and what string those combine into is the provider's
 * decision, published in their documentation. Here that document is
 * {@code com.example.thirdparty.paygate.CallbackSigner} — the sending half — and this class mirrors it.
 * The mirroring is checked by a test in which the stub signs and this verifies; two independent
 * implementations of one scheme is exactly the arrangement in which a mismatch is a silent 401 storm.
 *
 * <p><strong>The bean's existence is the switch.</strong> {@code ReplayProtectionFilter} is registered
 * {@code @ConditionalOnBean} on this type, so {@code replay.enabled=true} with no verifier starts an
 * application that logs nothing and accepts every unsigned request. S2 proves that by deleting its
 * {@code @Component} and watching five assertions flip from 401 to 200.
 *
 * <p>Comparison is constant-time. A byte-by-byte early exit leaks how much of a forged signature was
 * right, which over enough attempts is how a signature is guessed one byte at a time.
 */
@Component
class GatewayCallbackSignatureVerifier implements RequestSignatureVerifier {

  private static final String ALGORITHM = "HmacSHA256";

  private final byte[] secret;

  GatewayCallbackSignatureVerifier(@Value("${payments.gateway.callback-secret}") String secret) {
    this.secret = secret.getBytes(StandardCharsets.UTF_8);
  }

  @Override
  public boolean verify(SignedRequest request) {
    // <epochSeconds>.<nonce>.<body> — the provider's canonical form. Binding the timestamp and nonce into
    // the signed string is what stops a captured body being replayed with fresh headers; a scheme that
    // signs the body alone gives an attacker a signature that never expires.
    String canonical =
        request.timestamp().getEpochSecond()
            + "."
            + (request.nonce() == null ? "" : request.nonce())
            + "."
            + request.body();
    return MessageDigest.isEqual(
        hex(canonical).getBytes(StandardCharsets.UTF_8),
        request.signature().getBytes(StandardCharsets.UTF_8));
  }

  private String hex(String canonical) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(secret, ALGORITHM));
      return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 must be available", e);
    }
  }
}

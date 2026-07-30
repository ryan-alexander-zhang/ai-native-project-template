package com.aipersimmon.ddd.web.spring;

import com.aipersimmon.ddd.web.spi.ReplayGuard;
import com.aipersimmon.ddd.web.spi.RequestSignatureVerifier;
import com.aipersimmon.ddd.web.spi.SignedRequest;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects a captured, already-signed request that is replayed: it verifies the signature, checks
 * the timestamp is within tolerance, and — when nonce dedup is enabled — rejects a nonce seen
 * before. This is a security control, distinct from idempotency. Failures are written as {@code
 * application/problem+json} directly, since the filter runs before the dispatcher.
 */
public class ReplayProtectionFilter extends OncePerRequestFilter {

  private final RequestSignatureVerifier verifier;
  private final ReplayGuard replayGuard;
  private final ProblemHttpResponseWriter problemWriter;
  private final Clock clock;
  private final Duration tolerance;
  private final String signatureHeader;
  private final String timestampHeader;
  private final boolean nonceEnabled;
  private final String nonceHeader;
  private final int maxBodyBytes;

  public ReplayProtectionFilter(
      RequestSignatureVerifier verifier,
      ReplayGuard replayGuard,
      ProblemHttpResponseWriter problemWriter,
      Clock clock,
      Duration tolerance,
      String signatureHeader,
      String timestampHeader,
      boolean nonceEnabled,
      String nonceHeader,
      int maxBodyBytes) {
    this.verifier = verifier;
    this.replayGuard = replayGuard;
    this.problemWriter = problemWriter;
    this.clock = clock;
    this.tolerance = tolerance;
    this.signatureHeader = signatureHeader;
    this.timestampHeader = timestampHeader;
    this.nonceEnabled = nonceEnabled;
    this.nonceHeader = nonceHeader;
    this.maxBodyBytes = maxBodyBytes;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String signature = request.getHeader(signatureHeader);
    String nonce = nonceEnabled ? request.getHeader(nonceHeader) : null;
    String headerRejection = checkHeaders(signature, request.getHeader(timestampHeader), nonce);
    if (headerRejection != null) {
      reject(response, headerRejection);
      return;
    }
    Instant timestamp = parseEpochSecond(request.getHeader(timestampHeader));

    // Everything above is a header check, and none of it establishes who is calling — a current
    // timestamp and a non-empty signature header cost an attacker nothing. Buffering the body is
    // the first expensive thing this filter does, and the signature cannot be checked until it is
    // done, so the cap is what stands between an anonymous caller and an allocation of their
    // choosing.
    CachedBodyRequestWrapper cached;
    try {
      cached = new CachedBodyRequestWrapper(request, maxBodyBytes);
    } catch (CachedBodyRequestWrapper.BodyTooLargeException tooLarge) {
      problemWriter.write(
          response,
          HttpStatus.PAYLOAD_TOO_LARGE,
          "/problems/request-too-large",
          "Request body exceeds " + tooLarge.limit() + " bytes",
          Map.of());
      return;
    }

    String authRejection = verifyAuthenticity(signature, timestamp, nonce, cached);
    if (authRejection != null) {
      reject(response, authRejection);
      return;
    }

    filterChain.doFilter(cached, response);
  }

  /**
   * The checks that can be made from headers alone. Returns a rejection reason, or {@code null} if
   * the request is worth reading a body for.
   */
  private String checkHeaders(String signature, String timestampRaw, String nonce) {
    if (isBlank(signature) || isBlank(timestampRaw)) {
      return "Missing signature or timestamp";
    }
    Instant timestamp = parseEpochSecond(timestampRaw);
    if (timestamp == null) {
      return "Malformed timestamp";
    }
    if (isStale(timestamp)) {
      return "Request timestamp outside tolerance";
    }
    if (nonceEnabled && isBlank(nonce)) {
      return "Missing nonce";
    }
    return null;
  }

  /** The epoch-second timestamp, or {@code null} if it is not a parseable number. */
  private static Instant parseEpochSecond(String raw) {
    try {
      return Instant.ofEpochSecond(Long.parseLong(raw.trim()));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** Whether {@code timestamp} is further from now than the configured tolerance (either way). */
  private boolean isStale(Instant timestamp) {
    return Duration.between(timestamp, clock.instant()).abs().compareTo(tolerance) > 0;
  }

  /**
   * The body-dependent authenticity checks: the signature must verify over the cached body, and —
   * when nonce dedup is on — the nonce must not have been seen before. Returns a rejection reason,
   * or {@code null} if the request is authentic and fresh.
   */
  private String verifyAuthenticity(
      String signature, Instant timestamp, String nonce, CachedBodyRequestWrapper cached)
      throws IOException {
    if (!verifier.verify(new SignedRequest(signature, cached.bodyAsString(), timestamp, nonce))) {
      return "Invalid signature";
    }
    if (nonceEnabled && replayGuard.seenBefore(nonce, tolerance.multipliedBy(2))) {
      return "Replayed request";
    }
    return null;
  }

  private void reject(HttpServletResponse response, String detail) throws IOException {
    problemWriter.write(
        response, HttpStatus.UNAUTHORIZED, "/problems/replay-rejected", detail, Map.of());
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}

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

  public ReplayProtectionFilter(
      RequestSignatureVerifier verifier,
      ReplayGuard replayGuard,
      ProblemHttpResponseWriter problemWriter,
      Clock clock,
      Duration tolerance,
      String signatureHeader,
      String timestampHeader,
      boolean nonceEnabled,
      String nonceHeader) {
    this.verifier = verifier;
    this.replayGuard = replayGuard;
    this.problemWriter = problemWriter;
    this.clock = clock;
    this.tolerance = tolerance;
    this.signatureHeader = signatureHeader;
    this.timestampHeader = timestampHeader;
    this.nonceEnabled = nonceEnabled;
    this.nonceHeader = nonceHeader;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String signature = request.getHeader(signatureHeader);
    String timestampRaw = request.getHeader(timestampHeader);
    if (isBlank(signature) || isBlank(timestampRaw)) {
      reject(response, "Missing signature or timestamp");
      return;
    }

    Instant timestamp = parseEpochSecond(timestampRaw);
    if (timestamp == null) {
      reject(response, "Malformed timestamp");
      return;
    }
    if (isStale(timestamp)) {
      reject(response, "Request timestamp outside tolerance");
      return;
    }

    String nonce = nonceEnabled ? request.getHeader(nonceHeader) : null;
    if (nonceEnabled && isBlank(nonce)) {
      reject(response, "Missing nonce");
      return;
    }

    CachedBodyRequestWrapper cached = new CachedBodyRequestWrapper(request);
    String authRejection = verifyAuthenticity(signature, timestamp, nonce, cached);
    if (authRejection != null) {
      reject(response, authRejection);
      return;
    }

    filterChain.doFilter(cached, response);
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

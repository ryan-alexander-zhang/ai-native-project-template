package com.aipersimmon.ddd.web.spring;

import com.aipersimmon.ddd.tenancy.TenantContext;
import com.aipersimmon.ddd.web.spi.IdempotencyClaim;
import com.aipersimmon.ddd.web.spi.IdempotencyKey;
import com.aipersimmon.ddd.web.spi.IdempotencyPrincipalResolver;
import com.aipersimmon.ddd.web.spi.IdempotencyStore;
import com.aipersimmon.ddd.web.spi.StoredResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Makes an authorised write safe to retry: the first request under an idempotency key executes and
 * its outcome is stored; a later request with the same key receives that outcome instead of
 * executing again. Applies only to the configured methods and only when the key header is present
 * (a missing key is a 400 when {@code require-key} is set, otherwise it passes through). This is a
 * reliability concern — distinct from replay protection.
 *
 * <p><strong>The key is claimed before the request runs.</strong> Looking the key up, running, and
 * then saving the response cannot deliver "executed once": two concurrent first attempts both miss
 * the lookup and both execute, and the atomic save merely picks whose response is kept — after both
 * side effects have committed. That race is the ordinary one, not an exotic one: a client whose
 * first attempt timed out retries while it is still in flight, which is exactly why it sent a key.
 *
 * <p><strong>This filter must run after authentication.</strong> A key belongs to a caller: it is a
 * value one client invents, so without the caller in its identity, presenting a key someone else
 * used returns their response body. It is registered after the security filter chain so the
 * principal is established, and the resolved principal is part of the stored identity.
 */
public class IdempotencyFilter extends OncePerRequestFilter {

  /** See {@link #replayableHeaders}. Case-insensitive: header names are not case-sensitive. */
  private static final Set<String> REPLAYABLE_HEADERS =
      Set.of(
          HttpHeaders.CONTENT_TYPE.toLowerCase(Locale.ROOT),
          HttpHeaders.LOCATION.toLowerCase(Locale.ROOT),
          HttpHeaders.ETAG.toLowerCase(Locale.ROOT),
          HttpHeaders.CONTENT_LANGUAGE.toLowerCase(Locale.ROOT));

  private final IdempotencyStore store;
  private final IdempotencyPrincipalResolver principals;
  private final ProblemHttpResponseWriter problemWriter;
  private final String header;
  private final Duration ttl;
  private final Duration claimLease;
  private final boolean requireKey;
  private final Set<String> methods;

  public IdempotencyFilter(
      IdempotencyStore store,
      IdempotencyPrincipalResolver principals,
      ProblemHttpResponseWriter problemWriter,
      String header,
      Duration ttl,
      Duration claimLease,
      boolean requireKey,
      Set<String> methods) {
    this.store = store;
    this.principals = principals;
    this.problemWriter = problemWriter;
    this.header = header;
    this.ttl = ttl;
    this.claimLease = claimLease;
    this.requireKey = requireKey;
    this.methods = methods;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (!methods.contains(request.getMethod().toUpperCase(Locale.ROOT))) {
      filterChain.doFilter(request, response);
      return;
    }
    String rawKey = request.getHeader(header);
    if (rawKey == null || rawKey.isBlank()) {
      if (requireKey) {
        problemWriter.write(
            response,
            HttpStatus.BAD_REQUEST,
            "/problems/idempotency-key-required",
            "Missing " + header + " header",
            Map.of());
      } else {
        filterChain.doFilter(request, response);
      }
      return;
    }
    // The key is client-supplied and reaches a bounded column, so its length is validated here
    // rather
    // than surfacing later as a store failure on a request that has already executed.
    if (rawKey.length() > IdempotencyKey.MAX_KEY_LENGTH) {
      problemWriter.write(
          response,
          HttpStatus.BAD_REQUEST,
          "/problems/idempotency-key-too-long",
          header + " must be at most " + IdempotencyKey.MAX_KEY_LENGTH + " characters",
          Map.of());
      return;
    }

    IdempotencyKey key =
        new IdempotencyKey(
            TenantContext.effective().value(),
            principals.currentPrincipal().orElse(""),
            rawKey,
            fingerprint(request));

    IdempotencyClaim claim = store.claim(key, claimLease);
    if (claim instanceof IdempotencyClaim.Replay replay) {
      writeStored(response, replay.response());
      return;
    }
    if (claim instanceof IdempotencyClaim.InProgress) {
      // No outcome to return yet and executing would duplicate the side effect, so the honest
      // answer
      // is "ask again shortly" rather than a second execution or a fabricated success.
      response.setHeader(HttpHeaders.RETRY_AFTER, "1");
      problemWriter.write(
          response,
          HttpStatus.CONFLICT,
          "/problems/idempotency-in-progress",
          "A request with this " + header + " is still being processed",
          Map.of());
      return;
    }
    if (claim instanceof IdempotencyClaim.Mismatch) {
      problemWriter.write(
          response,
          HttpStatus.UNPROCESSABLE_ENTITY,
          "/problems/idempotency-key-reused",
          "This " + header + " was used for a different request",
          Map.of());
      return;
    }

    execute(request, response, filterChain, key);
  }

  /**
   * Runs the request under a won claim and settles it.
   *
   * <p>Two ordering details matter. The response is copied out in a {@code finally}, so a store
   * failure while settling cannot swallow a response whose side effect has already committed. And a
   * 5xx abandons the claim instead of completing it: freezing a transient failure under the key for
   * the whole retention window would answer every later retry with that failure, defeating the
   * retry the key was issued for.
   */
  private void execute(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain,
      IdempotencyKey key)
      throws ServletException, IOException {
    ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
    boolean executed = false;
    try {
      filterChain.doFilter(request, wrapper);
      executed = true;
      settle(key, wrapper);
    } finally {
      if (!executed) {
        // The handler threw: nothing was decided, so release the key rather than leaving it claimed
        // until the lease expires. The exception continues to the error handling that owns it.
        abandonQuietly(key);
      }
      wrapper.copyBodyToResponse();
    }
  }

  private void settle(IdempotencyKey key, ContentCachingResponseWrapper wrapper) {
    if (wrapper.getStatus() >= HttpStatus.INTERNAL_SERVER_ERROR.value()) {
      abandonQuietly(key);
      return;
    }
    store.complete(
        key,
        new StoredResponse(
            wrapper.getStatus(), wrapper.getContentAsByteArray(), replayableHeaders(wrapper)),
        ttl);
  }

  /**
   * A failed release is not worth failing the request over: the claim lease expires on its own, so
   * the cost is that this key is unusable until it does — strictly better than turning a completed
   * write into a 500 the client will retry.
   */
  private void abandonQuietly(IdempotencyKey key) {
    try {
      store.abandon(key);
    } catch (RuntimeException e) {
      logger.warn("could not release the idempotency claim; it will expire with its lease", e);
    }
  }

  /**
   * A digest of what was requested, so one key cannot answer for two different requests.
   *
   * <p>Deliberately built from the request line and content descriptors — method, path, query,
   * content type and length — and <em>not</em> from the body. Buffering every request body to hash
   * it would add a memory cost an unauthenticated caller can trigger, and the leak this guard
   * exists alongside (one caller reading another's response) is closed by the principal in the key,
   * not by the digest. The trade is explicit: a key reused against a different endpoint or a
   * differently shaped payload is caught; two distinct bodies of identical length and type against
   * the same endpoint are not.
   */
  private static String fingerprint(HttpServletRequest request) {
    StringBuilder material =
        new StringBuilder()
            .append(request.getMethod())
            .append('\n')
            .append(request.getRequestURI() == null ? "" : request.getRequestURI())
            .append('\n')
            .append(request.getQueryString() == null ? "" : request.getQueryString())
            .append('\n')
            .append(request.getContentType() == null ? "" : request.getContentType())
            .append('\n')
            .append(request.getContentLengthLong());
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(material.toString().getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by the JDK and must be present", e);
    }
  }

  /**
   * The headers that belong to the stored outcome rather than to the exchange that produced it.
   *
   * <p>An allow-list, not a full copy, and not {@code Content-Type} alone. {@code Location} is what
   * a {@code 201 Created} means — RFC 9110 §15.3.2 defines the status code in terms of it — and a
   * client retries precisely because it never saw the first response, so a replay without it
   * answers "you did not create a second one" while withholding where the first one is. {@code
   * ETag} and {@code Content-Language} describe the representation and travel with it for the same
   * reason.
   *
   * <p>Copying every header would be wrong: {@code Date}, {@code Set-Cookie} and the connection
   * headers describe one exchange and re-emitting them later is at best stale and at worst a
   * security bug. Making the set explicit turns "which headers carry the response's meaning" into a
   * decision that can be read and argued with, instead of an omission.
   */
  private static Map<String, String> replayableHeaders(ContentCachingResponseWrapper wrapper) {
    Map<String, String> headers = new LinkedHashMap<>();
    // Read the content type through its own accessor: a servlet container may hold it in a
    // dedicated
    // field rather than among the response headers, in which case it never shows up in
    // getHeaderNames() (Tomcat does exactly this).
    String contentType = wrapper.getContentType();
    if (contentType != null) {
      headers.put(HttpHeaders.CONTENT_TYPE, contentType);
    }
    for (String name : wrapper.getHeaderNames()) {
      String value = wrapper.getHeader(name);
      if (value != null && REPLAYABLE_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
        headers.put(name, value);
      }
    }
    return headers;
  }

  private void writeStored(HttpServletResponse response, StoredResponse stored) throws IOException {
    response.setStatus(stored.status());
    stored.headers().forEach(response::setHeader);
    response.getOutputStream().write(stored.body());
  }
}

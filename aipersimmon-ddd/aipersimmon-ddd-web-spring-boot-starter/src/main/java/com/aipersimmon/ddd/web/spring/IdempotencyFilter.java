package com.aipersimmon.ddd.web.spring;

import com.aipersimmon.ddd.web.spi.IdempotencyStore;
import com.aipersimmon.ddd.web.spi.StoredResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Makes an authorised write safe to retry: the first request under an idempotency key runs and its
 * response is stored; a later request with the same key replays that stored response instead of
 * executing again. Applies only to the configured methods and only when the key header is present
 * (a missing key is a 400 when {@code require-key} is set, otherwise it passes through). This is a
 * reliability concern — distinct from replay protection.
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
  private final ProblemHttpResponseWriter problemWriter;
  private final String header;
  private final Duration ttl;
  private final boolean requireKey;
  private final Set<String> methods;

  public IdempotencyFilter(
      IdempotencyStore store,
      ProblemHttpResponseWriter problemWriter,
      String header,
      Duration ttl,
      boolean requireKey,
      Set<String> methods) {
    this.store = store;
    this.problemWriter = problemWriter;
    this.header = header;
    this.ttl = ttl;
    this.requireKey = requireKey;
    this.methods = methods;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (!methods.contains(request.getMethod().toUpperCase())) {
      filterChain.doFilter(request, response);
      return;
    }
    String key = request.getHeader(header);
    if (key == null || key.isBlank()) {
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

    Optional<StoredResponse> replay = store.find(key);
    if (replay.isPresent()) {
      writeStored(response, replay.get());
      return;
    }

    ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
    filterChain.doFilter(request, wrapper);

    byte[] body = wrapper.getContentAsByteArray();
    store.saveIfAbsent(
        key, new StoredResponse(wrapper.getStatus(), body, replayableHeaders(wrapper)), ttl);
    wrapper.copyBodyToResponse();
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
    // dedicated field rather than among the response headers, in which case it never shows up in
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

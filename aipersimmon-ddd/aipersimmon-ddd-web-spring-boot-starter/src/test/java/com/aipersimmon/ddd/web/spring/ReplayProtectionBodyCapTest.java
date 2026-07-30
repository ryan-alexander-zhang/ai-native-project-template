package com.aipersimmon.ddd.web.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.web.spi.RequestSignatureVerifier;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The body buffer's cap.
 *
 * <p>The signature covers the body, so the body has to be in memory before the request can be shown
 * to be authentic. Everything the filter checks first — a non-empty signature header, a timestamp
 * within tolerance — costs an anonymous caller nothing to supply, so before this cap existed any
 * such caller could choose how much heap the buffer took.
 */
class ReplayProtectionBodyCapTest {

  private static final Instant NOW = Instant.parse("2026-07-30T12:00:00Z");
  private static final int CAP = 1024;

  private final AtomicInteger verifierCalls = new AtomicInteger();

  private ReplayProtectionFilter filter(RequestSignatureVerifier verifier) {
    return new ReplayProtectionFilter(
        verifier,
        (nonce, ttl) -> false,
        new ProblemHttpResponseWriter(new com.fasterxml.jackson.databind.ObjectMapper()),
        Clock.fixed(NOW, ZoneOffset.UTC),
        Duration.ofMinutes(5),
        "X-Signature",
        "X-Timestamp",
        false,
        "X-Nonce",
        CAP);
  }

  private MockHttpServletRequest signedRequest(byte[] body) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/orders");
    request.addHeader("X-Signature", "anything");
    request.addHeader("X-Timestamp", Long.toString(NOW.getEpochSecond()));
    request.setContent(body);
    return request;
  }

  @Test
  void aBodyOverTheCapIsRefusedWith413AndNeverReachesTheVerifier() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter(signed()).doFilter(signedRequest(new byte[CAP + 1]), response, chain);

    assertEquals(413, response.getStatus());
    assertEquals(0, verifierCalls.get(), "the cap must be reached before any work on the body");
    assertTrue(response.getContentAsString().contains("request-too-large"));
    assertFalse(chain.getRequest() != null, "the request must not be passed down the chain");
  }

  @Test
  void aBodyExactlyAtTheCapIsAccepted() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter(signed()).doFilter(signedRequest(new byte[CAP]), response, chain);

    assertEquals(200, response.getStatus());
    assertEquals(1, verifierCalls.get());
  }

  /**
   * The refusal has to happen while reading. A wrapper that reads the stream to the end and then
   * checks {@code length} has already made the allocation the cap exists to prevent, and would pass
   * every assertion above — so this one counts the bytes the stream was actually asked for.
   */
  @Test
  void theStreamIsNotDrainedPastTheCap() {
    CountingStream stream = new CountingStream(new byte[100 * CAP]);
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/orders") {
          @Override
          public ServletInputStream getInputStream() {
            return stream;
          }
        };

    assertThrows(
        CachedBodyRequestWrapper.BodyTooLargeException.class,
        () -> new CachedBodyRequestWrapper(request, CAP));

    assertTrue(
        stream.read < 100 * CAP,
        "the whole body was read before refusing it: " + stream.read + " bytes");
  }

  private RequestSignatureVerifier signed() {
    return request -> {
      verifierCalls.incrementAndGet();
      return true;
    };
  }

  /** Reports how many bytes were actually pulled from it. */
  private static final class CountingStream extends ServletInputStream {
    private final ByteArrayInputStream delegate;
    private int read;

    CountingStream(byte[] body) {
      this.delegate = new ByteArrayInputStream(body);
    }

    @Override
    public int read(byte[] buffer, int off, int len) {
      int n = delegate.read(buffer, off, len);
      if (n > 0) {
        read += n;
      }
      return n;
    }

    @Override
    public int read() {
      int b = delegate.read();
      if (b >= 0) {
        read++;
      }
      return b;
    }

    @Override
    public boolean isFinished() {
      return delegate.available() == 0;
    }

    @Override
    public boolean isReady() {
      return true;
    }

    @Override
    public void setReadListener(ReadListener listener) {
      throw new UnsupportedOperationException();
    }
  }

  @Test
  void theCachedBodyIsStillReadableTwice() throws IOException {
    MockHttpServletRequest request = signedRequest("hello".getBytes(StandardCharsets.UTF_8));

    CachedBodyRequestWrapper cached = new CachedBodyRequestWrapper(request, CAP);

    assertEquals("hello", cached.bodyAsString());
    assertEquals(
        "hello", new String(cached.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
  }
}

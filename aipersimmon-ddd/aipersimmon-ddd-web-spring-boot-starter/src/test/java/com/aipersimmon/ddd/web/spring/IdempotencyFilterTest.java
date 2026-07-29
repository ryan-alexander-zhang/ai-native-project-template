package com.aipersimmon.ddd.web.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * With idempotency enabled, a repeat request under the same key replays the first response without
 * running the handler again, while a different key executes anew — and the replay is the same
 * <em>answer</em>, not merely the same bytes: the headers that carry the response's meaning come
 * back with it.
 */
@SpringBootTest(
    classes = IdempotencyFilterTest.Config.class,
    properties = "aipersimmon.ddd.web.idempotency.enabled=true")
@AutoConfigureMockMvc
class IdempotencyFilterTest {

  @Autowired MockMvc mvc;

  @Autowired Counter counter;

  @Test
  void sameKeyReplaysFirstResponseAndRunsHandlerOnce() throws Exception {
    mvc.perform(post("/idem").header("Idempotency-Key", "k1"))
        .andExpect(status().isOk())
        .andExpect(content().string("1"));

    mvc.perform(post("/idem").header("Idempotency-Key", "k1"))
        .andExpect(status().isOk())
        .andExpect(content().string("1"));

    // A different key executes the handler again.
    mvc.perform(post("/idem").header("Idempotency-Key", "k2"))
        .andExpect(status().isOk())
        .andExpect(content().string("2"));
  }

  /**
   * A retried create is the reason idempotency exists, and {@code 201 Created} says where the
   * resource is in its {@code Location} header — RFC 9110 §15.3.2 makes that part of the status
   * code's meaning, not decoration. A client retries precisely because it never saw the first
   * response, so a replay that drops {@code Location} tells it "you did not order twice" while
   * withholding the one thing it asked for. {@code ETag} and {@code Content-Language} are on the
   * same footing.
   *
   * <p>Headers outside that allow-list are deliberately not replayed: {@code Date} and {@code
   * Set-Cookie} belong to the exchange that produced them, not to the stored outcome.
   */
  @Test
  void aReplayedCreateKeepsTheHeadersThatCarryItsMeaning() throws Exception {
    MockHttpServletResponse first =
        mvc.perform(post("/idem/create").header("Idempotency-Key", "c1"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse();
    assertNotNull(first.getHeader(HttpHeaders.LOCATION), "the create says where the resource is");

    mvc.perform(post("/idem/create").header("Idempotency-Key", "c1"))
        .andExpect(status().isCreated())
        .andExpect(header().string(HttpHeaders.LOCATION, first.getHeader(HttpHeaders.LOCATION)))
        .andExpect(header().string(HttpHeaders.ETAG, first.getHeader(HttpHeaders.ETAG)))
        .andExpect(
            header().string(HttpHeaders.CONTENT_TYPE, first.getHeader(HttpHeaders.CONTENT_TYPE)))
        .andExpect(header().doesNotExist("X-Trace-Note"));
  }

  /**
   * A 5xx must not be frozen under the key. The client retries a transient failure precisely
   * because it was transient, so replaying the failure for the whole retention window would answer
   * every retry with it and defeat the key's purpose. The claim is released instead, and the next
   * attempt executes.
   */
  @Test
  void aServerErrorIsNotStoredSoTheRetryStillExecutes() throws Exception {
    mvc.perform(post("/idem/fail").header("Idempotency-Key", "e1"))
        .andExpect(status().isInternalServerError());

    // Same key, and the handler runs again rather than replaying the 500.
    mvc.perform(post("/idem/fail").header("Idempotency-Key", "e1"))
        .andExpect(status().isInternalServerError());
    assertEquals(2, counter.failures.get(), "the failing handler ran on both attempts");
  }

  /** A 4xx is a decided outcome, so it is stored and replayed like any other. */
  @Test
  void aClientErrorIsADecidedOutcomeAndIsReplayed() throws Exception {
    mvc.perform(post("/idem/reject").header("Idempotency-Key", "r1"))
        .andExpect(status().isConflict());
    mvc.perform(post("/idem/reject").header("Idempotency-Key", "r1"))
        .andExpect(status().isConflict());
    assertEquals(1, counter.rejections.get(), "the second attempt replayed rather than executing");
  }

  /**
   * The same key against a different endpoint is neither replayable (the stored outcome answers a
   * different question) nor executable (the caller believes the key names one operation), so it is
   * refused.
   */
  @Test
  void aKeyReusedForADifferentRequestIsRefused() throws Exception {
    mvc.perform(post("/idem").header("Idempotency-Key", "x1")).andExpect(status().isOk());

    mvc.perform(post("/idem/create").header("Idempotency-Key", "x1"))
        .andExpect(status().isUnprocessableEntity());
  }

  /** A key longer than the store's column is rejected before the handler runs, not after. */
  @Test
  void anOversizedKeyIsRejectedBeforeExecuting() throws Exception {
    int before = counter.value.get();
    mvc.perform(post("/idem").header("Idempotency-Key", "k".repeat(256)))
        .andExpect(status().isBadRequest());
    assertEquals(before, counter.value.get(), "the handler must not have run");
  }

  static class Counter {
    final AtomicInteger value = new AtomicInteger();
    final AtomicInteger failures = new AtomicInteger();
    final AtomicInteger rejections = new AtomicInteger();
  }

  @RestController
  static class IdemController {

    private final Counter counter;

    IdemController(Counter counter) {
      this.counter = counter;
    }

    @PostMapping("/idem")
    String create() {
      return Integer.toString(counter.value.incrementAndGet());
    }

    /** A transient server failure: nothing worth replaying. */
    @PostMapping("/idem/fail")
    ResponseEntity<String> fail() {
      counter.failures.incrementAndGet();
      return ResponseEntity.internalServerError().body("boom");
    }

    /** A decided rejection: a real outcome, so it is stored and replayed. */
    @PostMapping("/idem/reject")
    ResponseEntity<String> reject() {
      counter.rejections.incrementAndGet();
      return ResponseEntity.status(409).body("conflict");
    }

    /** A create: 201 plus the headers that say what was created and where. */
    @PostMapping("/idem/create")
    ResponseEntity<String> createResource() {
      String id = Integer.toString(counter.value.incrementAndGet());
      return ResponseEntity.created(java.net.URI.create("/idem/" + id))
          .eTag("\"" + id + "\"")
          .header("X-Trace-Note", "per-exchange, not part of the outcome")
          .body(id);
    }
  }

  @Configuration
  @EnableAutoConfiguration
  @Import(IdemController.class)
  static class Config {
    @org.springframework.context.annotation.Bean
    Counter counter() {
      return new Counter();
    }
  }
}

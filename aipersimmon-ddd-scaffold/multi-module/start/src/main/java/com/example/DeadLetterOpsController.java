package com.example;

import com.aipersimmon.ddd.cqrs.page.Cursor;
import com.aipersimmon.ddd.cqrs.page.Slice;
import com.aipersimmon.ddd.outbox.DeadLetter;
import com.aipersimmon.ddd.outbox.DeadLetterStore;
import com.aipersimmon.ddd.outbox.DeadLetters;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The operator's side of the outbox: what the relay gave up on, and how to send it again.
 *
 * <p>A dead letter is not a lost message — {@code DeadLetterStore.store} moves the row out of the
 * outbox in the same transaction that gives up on it, so it is set aside rather than dropped. But
 * "set aside" only has value if someone can see it and act on it, and until this endpoint existed
 * nothing in the application could. That is the whole feature: fix whatever made delivery
 * impossible, then replay.
 *
 * <p>It lives in the composition root rather than in a bounded context because the outbox is not
 * anyone's domain — every context's integration events pass through the same table. A real
 * deployment puts this behind an operator role; the scaffold has no security context, so it is
 * simply mounted under {@code /ops}.
 *
 * <p>Both halves come from ports: {@link DeadLetters} reads and {@link DeadLetterStore} replays, so
 * this class holds no SQL and knows nothing about the {@code aipersimmon_dead_letter} table it is
 * showing (it used to have to — issue-00066). The listing pages by opaque cursor exactly like
 * {@code GET /orders} does, because it is the same read-model shape.
 */
@RestController
@RequestMapping("/ops/dead-letters")
@Tag(name = "Operations", description = "Inspect and replay messages the outbox relay gave up on")
public class DeadLetterOpsController {

  /** Above this a "listing" is a database dump; an operator pages instead. */
  private static final int MAX_PAGE = 200;

  private final DeadLetters deadLetters;
  private final DeadLetterStore store;

  public DeadLetterOpsController(DeadLetters deadLetters, DeadLetterStore store) {
    this.deadLetters = deadLetters;
    this.store = store;
  }

  /** What the relay gave up on, most recent failure first. */
  @Operation(summary = "List messages the relay gave up on")
  @ApiResponse(responseCode = "200", description = "A page of the dead letters currently held.")
  @GetMapping
  public Slice<DeadLetter> list(
      @Parameter(description = "Cursor from the previous page; omit for the first page.")
          @RequestParam(required = false)
          String cursor,
      @Parameter(description = "Page size; clamped here.", example = "20")
          @RequestParam(defaultValue = "20")
          int size) {
    return deadLetters.list(
        cursor == null ? null : Cursor.of(cursor), Math.clamp(size, 1, MAX_PAGE));
  }

  /** One dead letter, for an operator who arrived with an id from an alert or a log line. */
  @Operation(summary = "Fetch one dead letter by event id")
  @ApiResponse(responseCode = "200", description = "Why that message was given up on.")
  @ApiResponse(responseCode = "404", description = "No dead letter with that id.")
  @GetMapping("/{eventId}")
  public ResponseEntity<DeadLetter> get(
      @Parameter(description = "The dead letter's event id.") @PathVariable String eventId) {
    return deadLetters
        .find(eventId)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  /**
   * Put one message back in the outbox for another attempt. Idempotent in the way that matters: the
   * row keeps its original {@code event_id}, so a consumer that already saw the message before
   * delivery broke recognises the replay through the inbox and does not process it twice. Replaying
   * before fixing the cause simply produces the same dead letter again.
   */
  @Operation(summary = "Requeue a dead letter for delivery")
  @ApiResponse(responseCode = "204", description = "Requeued.")
  @ApiResponse(responseCode = "404", description = "No dead letter with that id.")
  @PostMapping("/{eventId}/replay")
  public ResponseEntity<Void> replay(
      @Parameter(description = "The dead letter's event id.") @PathVariable String eventId) {
    return store.replay(eventId)
        ? ResponseEntity.noContent().build()
        : ResponseEntity.notFound().build();
  }
}

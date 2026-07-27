package com.example;

import com.aipersimmon.ddd.outbox.DeadLetterStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
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
 * <h2>Why the listing is hand-written SQL</h2>
 *
 * <p>{@link DeadLetterStore} offers {@code store} and {@code replay} and nothing that reads, so
 * there is no supported way to answer "what is in there". Replay takes an {@code eventId} the
 * operator has no means of obtaining from the framework — the port assumes they already know it,
 * which is only true if they found it by querying the table themselves. So {@link DeadLetterMapper}
 * reads the table directly, coupling the sample to a schema it does not own (issue-00066).
 */
@RestController
@RequestMapping("/ops/dead-letters")
@Tag(name = "Operations", description = "Inspect and replay messages the outbox relay gave up on")
public class DeadLetterOpsController {

  private final DeadLetterStore deadLetters;
  private final DeadLetterMapper mapper;

  public DeadLetterOpsController(DeadLetterStore deadLetters, DeadLetterMapper mapper) {
    this.deadLetters = deadLetters;
    this.mapper = mapper;
  }

  /** What the relay gave up on, most recent first. */
  @Operation(summary = "List messages the relay gave up on")
  @ApiResponse(responseCode = "200", description = "The dead letters currently held.")
  @GetMapping
  public List<DeadLetter> list(
      @Parameter(description = "Maximum rows to return.", example = "50")
          @RequestParam(defaultValue = "50")
          int limit) {
    return mapper.recent(Math.min(Math.max(limit, 1), 200));
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
    return deadLetters.replay(eventId)
        ? ResponseEntity.noContent().build()
        : ResponseEntity.notFound().build();
  }

  /** One row of the dead-letter listing. */
  public record DeadLetter(
      String eventId,
      String type,
      int version,
      int attempts,
      String reason,
      String lastError,
      Instant failedAt) {}
}

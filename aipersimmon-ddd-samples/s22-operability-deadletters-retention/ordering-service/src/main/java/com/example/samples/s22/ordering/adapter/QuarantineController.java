package com.example.samples.s22.ordering.adapter;

import com.aipersimmon.ddd.cqrs.page.Cursor;
import com.aipersimmon.ddd.cqrs.page.Slice;
import com.aipersimmon.ddd.outbox.DeadLetter;
import com.example.samples.s22.ordering.application.Quarantine;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The operator's three questions: what did we give up on, why this one, and try it again.
 *
 * <p>This is the endpoint whose absence is the actual bug in most deployments. The outbox will move a
 * spent message aside whether or not anyone can see it, so a service without this surface has a
 * table that silently accumulates the facts nobody downstream was ever told — and the first person to
 * look is doing it with psql, during an incident, against a schema they have never read.
 *
 * <p>Nothing here returns the payload. That is the library's choice ({@code DeadLetter} deliberately
 * is not the {@code OutboxMessage}) and it is worth keeping: triage asks "why did this not go out,
 * and is it worth replaying", which the payload answers for none, while a listing that carries every
 * message body is both expensive and a way to spill event contents onto an operations screen. An
 * operator who genuinely needs the body has database access and a reason.
 */
@RestController
@RequestMapping("/ops/dead-letters")
class QuarantineController {

  private final Quarantine quarantine;

  QuarantineController(Quarantine quarantine) {
    this.quarantine = quarantine;
  }

  /**
   * A page, newest give-up first. {@code after} is the opaque cursor from the previous page's
   * {@code nextCursor}, echoed back verbatim.
   */
  @GetMapping
  ResponseEntity<Map<String, Object>> list(
      @RequestParam(required = false) String after,
      @RequestParam(defaultValue = "20") int size) {
    Slice<DeadLetter> slice = quarantine.list(after == null ? null : Cursor.of(after), size);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("items", slice.items().stream().map(QuarantineController::body).toList());
    body.put("nextCursor", slice.nextCursor() == null ? null : slice.nextCursor().value());
    return ResponseEntity.ok(body);
  }

  @GetMapping("/{eventId}")
  ResponseEntity<Map<String, Object>> find(@PathVariable String eventId) {
    return quarantine
        .find(eventId)
        .map(QuarantineController::body)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  /**
   * Requeues it. 404 rather than 409 when nothing is held under the id, because that is also what
   * pressing the button a second time looks like, and an operator retrying a request they are not
   * sure went through should not be told they did something wrong.
   */
  @PostMapping("/{eventId}/replay")
  ResponseEntity<Map<String, Object>> replay(@PathVariable String eventId) {
    if (!quarantine.replay(eventId)) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.accepted().body(Map.of("eventId", eventId, "requeued", true));
  }

  /**
   * Everything triage needs and nothing it does not: which event, how hard the relay tried, why it
   * stopped, what the last failure said.
   *
   * <p>{@code reason} is the one an operator reads first, because the two values mean different work.
   * {@code RETRIES_EXHAUSTED} says the message is probably fine and the environment was not — look at
   * the destination, then replay. {@code PERMANENT} says the relay knew on the first failure that no
   * number of attempts would help — replaying changes nothing until the cause is gone.
   *
   * <p>{@code lastError} carries the failure <em>and its causes</em>, flattened onto one line, which is
   * what makes it worth reading at all. The distinction is not academic: a transport wraps, so the
   * commonest failure of all — a topic nobody provisioned — arrives as {@code KafkaException: Send
   * failed}, and the topic name and the reason are two levels down. This sample was written against a
   * library that recorded only the outer frame, measured the resulting emptiness, and filed
   * issue-00165; the fix flattens the chain, and {@code DeadLetterTest} asserts the difference.
   */
  private static Map<String, Object> body(DeadLetter letter) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("eventId", letter.eventId());
    map.put("type", letter.type());
    map.put("version", letter.version());
    map.put("subject", letter.subject());
    map.put("attempts", letter.attempts());
    map.put("reason", letter.reason().name());
    map.put("lastError", letter.lastError());
    map.put("failedAt", letter.failedAt() == null ? null : letter.failedAt().toString());
    map.put("occurredAt", letter.occurredAt() == null ? null : letter.occurredAt().toString());
    return map;
  }
}

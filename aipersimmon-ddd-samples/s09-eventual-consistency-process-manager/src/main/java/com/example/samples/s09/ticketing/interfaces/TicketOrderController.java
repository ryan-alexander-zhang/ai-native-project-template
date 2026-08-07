package com.example.samples.s09.ticketing.interfaces;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.QueryBus;
import com.example.samples.s09.ticketing.application.FindTicketOrder;
import com.example.samples.s09.ticketing.application.PlaceTicketOrder;
import com.example.samples.s09.ticketing.application.RequestCancellation;
import com.example.samples.s09.ticketing.application.TicketOrderView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The client's whole view of the system: place an order, ask about it, change your mind.
 *
 * <p><strong>The read answers from the aggregate, never from the flow.</strong> That is the practical
 * consequence of deciding who holds the truth: a client is told {@code PLACED}, {@code TICKETED} or
 * {@code CANCELLED}, and never {@code AWAITING_PAYMENT} — which is a fact about our coordinator's
 * bookkeeping and none of a customer's business. The flow's step is visible too, but on the operator
 * endpoint, which is where it belongs.
 *
 * <p>{@code POST} answers 202: an order exists, and everything else about it is going to happen later.
 */
@RestController
@RequestMapping("/orders")
class TicketOrderController {

  private final CommandBus commandBus;
  private final QueryBus queryBus;

  TicketOrderController(CommandBus commandBus, QueryBus queryBus) {
    this.commandBus = commandBus;
    this.queryBus = queryBus;
  }

  @PostMapping
  ResponseEntity<Map<String, String>> place(@Valid @RequestBody PlaceRequest request) {
    String id =
        commandBus.send(
            new PlaceTicketOrder(
                request.customerId(), request.seatClass(), request.amountMinor()));
    return ResponseEntity.accepted()
        .location(URI.create("/orders/" + id))
        .body(Map.of("id", id, "status", "PLACED"));
  }

  @GetMapping("/{id}")
  ResponseEntity<TicketOrderView> order(@PathVariable String id) {
    return queryBus
        .ask(new FindTicketOrder(id))
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  /**
   * The customer changing their mind. It answers 202 and not 200, because whether the order can still be
   * cancelled — and what has to be undone first — is the flow's decision, made after this returns.
   */
  @PostMapping("/{id}/cancellation")
  ResponseEntity<Map<String, String>> cancel(
      @PathVariable String id, @Valid @RequestBody CancelRequest request) {
    commandBus.send(new RequestCancellation(id, request.reason()));
    return ResponseEntity.accepted().body(Map.of("id", id, "requested", "cancellation"));
  }

  record PlaceRequest(
      @NotBlank String customerId, @NotBlank String seatClass, @Positive long amountMinor) {}

  record CancelRequest(@NotBlank String reason) {}
}

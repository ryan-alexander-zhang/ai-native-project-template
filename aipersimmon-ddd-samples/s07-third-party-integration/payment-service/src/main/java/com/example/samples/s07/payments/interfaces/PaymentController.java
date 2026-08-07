package com.example.samples.s07.payments.interfaces;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.QueryBus;
import com.example.samples.s07.payments.application.FindPayment;
import com.example.samples.s07.payments.application.PaymentView;
import com.example.samples.s07.payments.application.RequestPayment;
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
 * Our API — the half of the edge that our own clients use.
 *
 * <p>{@code POST} answers <strong>202 Accepted</strong>, not 201, and that is the honest status: what has
 * happened is that an intent has been recorded. Nothing has been sent to the provider yet, no money has
 * moved, and the outcome will arrive by a road the caller cannot watch. A 201 would imply a completed
 * resource; 200 with a made-up {@code "status": "processing"} would imply we know more than we do.
 *
 * <p>{@code GET} is therefore not a convenience, it is the other half of the contract. A caller that
 * cannot poll has no way to learn the outcome of something that is asynchronous by nature, and the
 * alternative it will reach for is asking again — which is why the response carries the payment's own
 * status rather than a summary of it.
 */
@RestController
@RequestMapping("/payments")
class PaymentController {

  private final CommandBus commandBus;
  private final QueryBus queryBus;

  PaymentController(CommandBus commandBus, QueryBus queryBus) {
    this.commandBus = commandBus;
    this.queryBus = queryBus;
  }

  @PostMapping
  ResponseEntity<Map<String, String>> request(@Valid @RequestBody RequestPaymentRequest request) {
    String id = commandBus.send(new RequestPayment(request.orderRef(), request.amountMinor()));
    return ResponseEntity.accepted()
        .location(URI.create("/payments/" + id))
        .body(Map.of("id", id, "status", "REQUESTED"));
  }

  @GetMapping("/{id}")
  ResponseEntity<PaymentView> payment(@PathVariable String id) {
    return queryBus
        .ask(new FindPayment(id))
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  record RequestPaymentRequest(@NotBlank String orderRef, @Positive long amountMinor) {}
}

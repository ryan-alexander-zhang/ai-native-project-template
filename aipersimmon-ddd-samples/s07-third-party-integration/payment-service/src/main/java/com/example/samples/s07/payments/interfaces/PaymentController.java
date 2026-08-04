package com.example.samples.s07.payments.interfaces;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.example.samples.s07.payments.application.RequestPayment;
import com.example.samples.s07.payments.domain.Payment;
import com.example.samples.s07.payments.domain.PaymentId;
import com.example.samples.s07.payments.domain.Payments;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.util.HashMap;
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
  private final Payments payments;

  PaymentController(CommandBus commandBus, Payments payments) {
    this.commandBus = commandBus;
    this.payments = payments;
  }

  @PostMapping
  ResponseEntity<Map<String, String>> request(@Valid @RequestBody RequestPaymentRequest request) {
    String id = commandBus.send(new RequestPayment(request.orderRef(), request.amountMinor()));
    return ResponseEntity.accepted()
        .location(URI.create("/payments/" + id))
        .body(Map.of("id", id, "status", "REQUESTED"));
  }

  @GetMapping("/{id}")
  ResponseEntity<Map<String, Object>> payment(@PathVariable String id) {
    return payments
        .find(new PaymentId(id))
        .map(PaymentController::body)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  /**
   * The review flag is exposed rather than hidden. A client integration that can see "this payment needs a
   * human" can stop asking and say so; one that cannot will poll a stuck payment forever and show the
   * customer a spinner.
   */
  private static Map<String, Object> body(Payment payment) {
    Map<String, Object> body = new HashMap<>();
    body.put("id", payment.id().value());
    body.put("orderRef", payment.orderRef());
    body.put("amountMinor", payment.amountMinor());
    body.put("status", payment.status().name());
    body.put("gatewayRef", payment.gatewayRef());
    body.put("needsReview", payment.needsReview());
    body.put("reviewReason", payment.reviewReason());
    return body;
  }

  record RequestPaymentRequest(@NotBlank String orderRef, @Positive long amountMinor) {}
}

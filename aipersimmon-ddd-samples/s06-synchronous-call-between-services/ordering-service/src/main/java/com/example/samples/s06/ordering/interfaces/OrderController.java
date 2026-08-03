package com.example.samples.s06.ordering.interfaces;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.example.samples.s06.ordering.application.PlaceOrder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The entry, and it has no idea a second service exists.
 *
 * <p>No try/catch: the two failures a synchronous dependency introduces both arrive as exceptions carrying
 * this context's error codes, and the framework's web layer renders each as an RFC 9457 problem with the
 * right status — 422 for a refusal, 503 for "no answer" (see {@code RiskProblemCatalog}). Writing the
 * mapping once, as data, beats writing it per endpoint.
 */
@RestController
@RequestMapping("/orders")
class OrderController {

  private final CommandBus commandBus;

  OrderController(CommandBus commandBus) {
    this.commandBus = commandBus;
  }

  @PostMapping
  ResponseEntity<Map<String, String>> place(@Valid @RequestBody PlaceOrderRequest request) {
    String id = commandBus.send(new PlaceOrder(request.customerId(), request.amountCents()));
    return ResponseEntity.created(URI.create("/orders/" + id)).body(Map.of("id", id));
  }

  record PlaceOrderRequest(@NotBlank String customerId, @Positive long amountCents) {}
}

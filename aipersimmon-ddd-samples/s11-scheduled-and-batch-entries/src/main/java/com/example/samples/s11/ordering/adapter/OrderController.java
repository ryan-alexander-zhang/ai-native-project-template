package com.example.samples.s11.ordering.adapter;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.example.samples.s11.ordering.application.PayOrder;
import com.example.samples.s11.ordering.application.PlaceOrder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The HTTP entry, for contrast: a different trigger, the same channel. The full treatment is S1. */
@RestController
@RequestMapping("/orders")
class OrderController {

  private final CommandBus commandBus;

  OrderController(CommandBus commandBus) {
    this.commandBus = commandBus;
  }

  @PostMapping
  ResponseEntity<Map<String, String>> place(@Valid @RequestBody PlaceOrderRequest request) {
    String id = commandBus.send(new PlaceOrder(request.customerId(), request.payWithinSeconds()));
    return ResponseEntity.created(URI.create("/orders/" + id)).body(Map.of("id", id));
  }

  @PostMapping("/{id}/payment")
  ResponseEntity<Void> pay(@PathVariable String id) {
    commandBus.send(new PayOrder(id));
    return ResponseEntity.noContent().build();
  }

  record PlaceOrderRequest(@NotBlank String customerId, @Positive int payWithinSeconds) {}
}

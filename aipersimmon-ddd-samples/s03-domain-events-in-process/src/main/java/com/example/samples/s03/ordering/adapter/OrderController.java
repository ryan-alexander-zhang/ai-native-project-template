package com.example.samples.s03.ordering.adapter;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.example.samples.s03.ordering.application.PlaceOrder;
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

/** Just enough to run the sample by hand; the tests drive the bus directly. */
@RestController
@RequestMapping("/orders")
class OrderController {

  private final CommandBus commandBus;

  OrderController(CommandBus commandBus) {
    this.commandBus = commandBus;
  }

  @PostMapping
  ResponseEntity<Map<String, String>> place(@Valid @RequestBody PlaceOrderRequest request) {
    String id =
        commandBus.send(
            new PlaceOrder(request.customerId(), request.firstOrder(), request.amountCents()));
    return ResponseEntity.created(URI.create("/orders/" + id)).body(Map.of("id", id));
  }

  record PlaceOrderRequest(
      @NotBlank String customerId, boolean firstOrder, @Positive long amountCents) {}
}

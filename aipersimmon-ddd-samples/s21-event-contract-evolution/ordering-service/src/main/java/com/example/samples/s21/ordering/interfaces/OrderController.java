package com.example.samples.s21.ordering.interfaces;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.example.samples.s21.ordering.application.PlaceOrder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The entry. It has its own request shape, versioned by the HTTP API, not by the event contract. */
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
            new PlaceOrder(
                request.customerId(),
                request.lines().stream()
                    .map(line -> new PlaceOrder.Line(line.sku(), line.quantity()))
                    .toList(),
                request.warehouseCode()));
    return ResponseEntity.created(URI.create("/orders/" + id)).body(Map.of("id", id));
  }

  record PlaceOrderRequest(
      @NotBlank String customerId,
      @NotEmpty List<@Valid LineRequest> lines,
      @NotBlank String warehouseCode) {}

  record LineRequest(@NotBlank String sku, @Positive int quantity) {}
}

package com.example.samples.s22.ordering.interfaces;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.example.samples.s22.ordering.application.PlaceOrder;
import com.example.samples.s22.ordering.domain.Order;
import com.example.samples.s22.ordering.domain.OrderId;
import com.example.samples.s22.ordering.domain.Orders;
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
 * The business entry, and it is ordinary on purpose: 201 comes back whether or not the broker is
 * reachable, because the announcement was a row in this database. Nothing a caller can see here
 * changes when delivery is broken — which is the reason the rest of this sample exists.
 */
@RestController
@RequestMapping("/orders")
class OrderController {

  private final CommandBus commandBus;
  private final Orders orders;

  OrderController(CommandBus commandBus, Orders orders) {
    this.commandBus = commandBus;
    this.orders = orders;
  }

  @PostMapping
  ResponseEntity<Map<String, String>> place(@Valid @RequestBody PlaceOrderRequest request) {
    String id =
        commandBus.send(new PlaceOrder(request.customerId(), request.sku(), request.quantity()));
    return ResponseEntity.created(URI.create("/orders/" + id)).body(Map.of("id", id));
  }

  @GetMapping("/{id}")
  ResponseEntity<Map<String, Object>> order(@PathVariable String id) {
    return orders
        .find(new OrderId(id))
        .map(
            order ->
                Map.<String, Object>of(
                    "id", order.id().value(),
                    "customerId", order.customerId(),
                    "sku", order.sku(),
                    "quantity", order.quantity()))
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  record PlaceOrderRequest(
      @NotBlank String customerId, @NotBlank String sku, @Positive int quantity) {}
}

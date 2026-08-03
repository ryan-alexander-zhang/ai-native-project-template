package com.example.samples.s04.ordering.interfaces;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.example.samples.s04.ordering.application.PlaceOrder;
import com.example.samples.s04.ordering.domain.Order;
import com.example.samples.s04.ordering.domain.OrderId;
import com.example.samples.s04.ordering.domain.Orders;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The entry. Unchanged by the fact that this service now talks to another one — S1 covers it.
 *
 * <p>Also unchanged by tenancy, which is the claim S13 makes here: no method reads a tenant, takes one
 * as a parameter, or passes one down. The tenant is resolved and bound by the framework's edge filter
 * before this class is reached, and everything below reads it from the ambient context. A tenant that
 * travels as a method parameter is a tenant that some method will eventually forget to pass.
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
        commandBus.send(
            new PlaceOrder(
                request.customerId(),
                request.lines().stream()
                    .map(line -> new PlaceOrder.Line(line.sku(), line.quantity()))
                    .toList(),
                request.draftOnly()));
    return ResponseEntity.created(URI.create("/orders/" + id)).body(Map.of("id", id));
  }

  /**
   * A read, so isolation is observable from outside: another tenant asking for this id gets 404. Not
   * 403 — a 403 confirms the id exists, and the caller is not entitled to know that either.
   */
  @GetMapping("/{id}")
  ResponseEntity<Map<String, Object>> order(@PathVariable String id) {
    return orders
        .find(new OrderId(id))
        .map(this::body)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  private Map<String, Object> body(Order order) {
    return Map.of(
        "id", order.id().value(),
        "customerId", order.customerId(),
        "lines",
            order.lines().stream()
                .map(line -> Map.of("sku", line.sku(), "quantity", line.quantity()))
                .toList());
  }

  record PlaceOrderRequest(
      @NotBlank String customerId, @NotEmpty List<@Valid LineRequest> lines, boolean draftOnly) {}

  record LineRequest(@NotBlank String sku, @Positive int quantity) {}
}

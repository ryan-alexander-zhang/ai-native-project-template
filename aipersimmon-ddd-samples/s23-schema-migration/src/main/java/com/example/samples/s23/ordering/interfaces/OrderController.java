package com.example.samples.s23.ordering.interfaces;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.example.samples.s23.ordering.application.BackfillHandling;
import com.example.samples.s23.ordering.application.PlaceOrder;
import com.example.samples.s23.ordering.domain.OrderId;
import com.example.samples.s23.ordering.domain.Orders;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The business entry, plus the one an operator drives the backfill from. */
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
                request.sku(),
                request.quantity(),
                request.street(),
                request.city()));
    return ResponseEntity.created(URI.create("/orders/" + id)).body(Map.of("id", id));
  }

  @GetMapping("/{id}")
  ResponseEntity<Map<String, Object>> order(@PathVariable String id) {
    return orders
        .find(new OrderId(id))
        .map(
            order -> {
              Map<String, Object> body = new LinkedHashMap<>();
              body.put("id", order.id().value());
              body.put("customerId", order.customerId());
              body.put("sku", order.sku());
              body.put("quantity", order.quantity());
              body.put("street", order.shipTo().street());
              body.put("city", order.shipTo().city());
              // Null while the backfill has not reached this row, and the read side says so rather than
              // guessing. A field that reported STANDARD for an undecided row would be a lie the API tells
              // for as long as the backfill takes.
              body.put("handling", order.handling().map(Enum::name).orElse(null));
              return body;
            })
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  /**
   * One page of the backfill, returning how many rows it decided.
   *
   * <p>An endpoint rather than a scheduled job, because a backfill is run <em>by someone</em>, watched, and
   * stopped if it misbehaves. Making it a schedule means it starts on the next deploy, at a time nobody
   * chose, and its progress is a log line. The caller loops until this returns zero.
   */
  @PostMapping("/handling-backfill")
  ResponseEntity<Map<String, Object>> backfill(@RequestParam(defaultValue = "100") int batchSize) {
    int decided = commandBus.send(new BackfillHandling(batchSize));
    return ResponseEntity.ok(Map.of("decided", decided));
  }

  record PlaceOrderRequest(
      @NotBlank String customerId,
      @NotBlank String sku,
      @Positive int quantity,
      @NotBlank String street,
      @NotBlank String city) {}
}

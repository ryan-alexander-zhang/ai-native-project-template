package com.example.samples.s23.ordering.adapter;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.QueryBus;
import com.example.samples.s23.ordering.application.BackfillHandling;
import com.example.samples.s23.ordering.application.FindOrder;
import com.example.samples.s23.ordering.application.OrderView;
import com.example.samples.s23.ordering.application.PlaceOrder;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The business entry, plus the one an operator drives the backfill from. */
@RestController
@RequestMapping("/orders")
class OrderController {

  private final CommandBus commandBus;
  private final QueryBus queryBus;

  OrderController(CommandBus commandBus, QueryBus queryBus) {
    this.commandBus = commandBus;
    this.queryBus = queryBus;
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
  ResponseEntity<OrderView> order(@PathVariable String id) {
    return queryBus
        .ask(new FindOrder(id))
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

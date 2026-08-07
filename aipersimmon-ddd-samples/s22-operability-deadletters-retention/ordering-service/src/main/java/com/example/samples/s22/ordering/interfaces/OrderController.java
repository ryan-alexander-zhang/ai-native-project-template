package com.example.samples.s22.ordering.interfaces;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.QueryBus;
import com.example.samples.s22.ordering.application.FindOrder;
import com.example.samples.s22.ordering.application.OrderView;
import com.example.samples.s22.ordering.application.PlaceOrder;
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
  private final QueryBus queryBus;

  OrderController(CommandBus commandBus, QueryBus queryBus) {
    this.commandBus = commandBus;
    this.queryBus = queryBus;
  }

  @PostMapping
  ResponseEntity<Map<String, String>> place(@Valid @RequestBody PlaceOrderRequest request) {
    String id =
        commandBus.send(new PlaceOrder(request.customerId(), request.sku(), request.quantity()));
    return ResponseEntity.created(URI.create("/orders/" + id)).body(Map.of("id", id));
  }

  @GetMapping("/{id}")
  ResponseEntity<OrderView> order(@PathVariable String id) {
    return queryBus
        .ask(new FindOrder(id))
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  record PlaceOrderRequest(
      @NotBlank String customerId, @NotBlank String sku, @Positive int quantity) {}
}

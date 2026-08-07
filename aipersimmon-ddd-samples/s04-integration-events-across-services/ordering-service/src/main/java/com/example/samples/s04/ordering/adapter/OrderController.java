package com.example.samples.s04.ordering.adapter;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.QueryBus;
import com.example.samples.s04.ordering.application.FindOrder;
import com.example.samples.s04.ordering.application.OrderView;
import com.example.samples.s04.ordering.application.PlaceOrder;
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
  ResponseEntity<OrderView> order(@PathVariable String id) {
    return queryBus
        .ask(new FindOrder(id))
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  record PlaceOrderRequest(
      @NotBlank String customerId, @NotEmpty List<@Valid LineRequest> lines, boolean draftOnly) {}

  record LineRequest(@NotBlank String sku, @Positive int quantity) {}
}

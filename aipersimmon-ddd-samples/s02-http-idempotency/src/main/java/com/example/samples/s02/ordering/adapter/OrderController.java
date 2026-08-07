package com.example.samples.s02.ordering.adapter;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.QueryBus;
import com.example.samples.s02.ordering.application.FindOrder;
import com.example.samples.s02.ordering.application.PlaceOrder;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Nothing here knows about idempotency. The filter runs before the request reaches this class and
 * after it produces a response, which is the point: making a write safe to retry is not the
 * controller's job and not the handler's.
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
  ResponseEntity<OrderResponse> place(@Valid @RequestBody PlaceOrderRequest request) {
    String id =
        commandBus.send(new PlaceOrder(request.clientReference(), request.amountCents()));
    return ResponseEntity.created(URI.create("/orders/" + id))
        .body(OrderResponse.of(queryBus.ask(new FindOrder(id))));
  }

  @GetMapping("/{id}")
  OrderResponse find(@PathVariable String id) {
    return OrderResponse.of(queryBus.ask(new FindOrder(id)));
  }
}

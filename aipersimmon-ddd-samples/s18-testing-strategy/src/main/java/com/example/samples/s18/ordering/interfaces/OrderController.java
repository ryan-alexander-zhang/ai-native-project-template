package com.example.samples.s18.ordering.interfaces;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.QueryBus;
import com.example.samples.s18.ordering.application.FindOrder;
import com.example.samples.s18.ordering.application.OrderView;
import com.example.samples.s18.ordering.application.PlaceOrder;
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
 * Translation only, which is exactly why a slice test is enough for it: with the two buses stubbed,
 * {@code @WebMvcTest} answers "does the body map to the right command, and does a rejection map to the
 * right problem" in a fraction of a second and with no container.
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
  ResponseEntity<OrderView> place(@Valid @RequestBody PlaceOrderRequest request) {
    String id = commandBus.send(new PlaceOrder(request.customerId(), request.amountCents()));
    return ResponseEntity.created(URI.create("/orders/" + id))
        .body(queryBus.ask(new FindOrder(id)));
  }

  @GetMapping("/{id}")
  OrderView find(@PathVariable String id) {
    return queryBus.ask(new FindOrder(id));
  }
}

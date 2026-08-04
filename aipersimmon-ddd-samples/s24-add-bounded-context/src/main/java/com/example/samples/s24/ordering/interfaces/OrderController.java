package com.example.samples.s24.ordering.interfaces;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.QueryBus;
import com.example.samples.s24.ordering.application.OrderQuery;
import com.example.samples.s24.ordering.application.OrderTotals;
import com.example.samples.s24.ordering.application.PlaceOrder;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ordering's edge, unchanged by the new context.
 *
 * <p>Which is worth one line of notice: adding a bounded context added one optional field to a request body and one
 * nullable field to a response. If adding a context requires the existing edges to be rearranged, the boundary was drawn
 * through the middle of something.
 */
@RestController
class OrderController {

  private final CommandBus commands;
  private final QueryBus queries;

  OrderController(CommandBus commands, QueryBus queries) {
    this.commands = commands;
    this.queries = queries;
  }

  record PlaceRequest(
      String orderId,
      String customerId,
      String currency,
      String couponCode,
      List<PlaceOrder.Line> lines) {}

  @PostMapping("/orders")
  ResponseEntity<OrderTotals> place(@Valid @RequestBody PlaceRequest request) {
    OrderTotals totals =
        commands.send(
            new PlaceOrder(
                request.orderId(),
                request.customerId(),
                request.currency(),
                request.couponCode(),
                request.lines()));
    return ResponseEntity.created(URI.create("/orders/" + totals.orderId())).body(totals);
  }

  @GetMapping("/orders/{id}")
  OrderTotals read(@PathVariable String id) {
    return queries.ask(new OrderQuery(id));
  }
}

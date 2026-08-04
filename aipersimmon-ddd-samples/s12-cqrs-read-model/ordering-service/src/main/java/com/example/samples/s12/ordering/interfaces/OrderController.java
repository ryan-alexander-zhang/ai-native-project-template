package com.example.samples.s12.ordering.interfaces;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.QueryBus;
import com.example.samples.s12.ordering.application.BrowseOrderList;
import com.example.samples.s12.ordering.application.OrderListItem;
import com.example.samples.s12.ordering.application.PayOrder;
import com.example.samples.s12.ordering.application.PlaceOrder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The ordering context's edge: two commands and one query.
 *
 * <p>The list endpoint returns {@code projectedAt} to the caller. That is a deliberate contract decision
 * rather than debug output: a read model is by nature as-of-some-moment, and a client that is told which
 * moment can decide what to do about it. Hiding it does not make the staleness go away, it only makes it
 * undiagnosable — and it is the difference between "the list is wrong" and "the list is nine seconds old".
 */
@RestController
class OrderController {

  private final CommandBus commandBus;
  private final QueryBus queryBus;

  OrderController(CommandBus commandBus, QueryBus queryBus) {
    this.commandBus = commandBus;
    this.queryBus = queryBus;
  }

  @PostMapping("/orders")
  Map<String, String> place(@Valid @RequestBody PlaceRequest request) {
    String orderId =
        commandBus.send(
            new PlaceOrder(
                request.customerId(),
                request.lines().stream()
                    .map(
                        line ->
                            new PlaceOrder.Line(
                                line.sku(), line.quantity(), line.unitPriceMinor()))
                    .toList()));
    return Map.of("orderId", orderId);
  }

  @PostMapping("/orders/{orderId}/pay")
  void pay(@PathVariable String orderId) {
    commandBus.send(new PayOrder(orderId));
  }

  @GetMapping("/orders")
  List<OrderListItem> list(
      @RequestParam String customerId, @RequestParam(defaultValue = "20") int limit) {
    return queryBus.ask(new BrowseOrderList(customerId, limit));
  }

  record PlaceRequest(@NotBlank String customerId, @NotEmpty @Valid List<LineRequest> lines) {}

  record LineRequest(@NotBlank String sku, @Positive int quantity, @Positive long unitPriceMinor) {}
}

package com.example.samples.s01.ordering.adapter;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.QueryBus;
import com.example.samples.s01.ordering.application.ConfirmOrder;
import com.example.samples.s01.ordering.application.FindOrder;
import com.example.samples.s01.ordering.application.OrderView;
import com.example.samples.s01.ordering.application.PlaceOrder;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Translation, and nothing else: request body to command, view to response body.
 *
 * <p>No {@code try}/{@code catch} and no {@code @ExceptionHandler} — the starter's advice owns the
 * mapping from exception to problem document, and catching anything here would put a hole in that one
 * contract. No {@code @Transactional} either: the command bus opens the transaction.
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

  /**
   * The 422 has to be declared by hand: the OpenAPI customizer documents 400, 404, 429 and 500 for
   * every operation, but not 409 or 422 — the two an endpoint's own rules decide.
   */
  @PostMapping
  @ApiResponse(responseCode = "422", description = "The order breaks a rule of the ordering context.")
  ResponseEntity<OrderResponse> place(@Valid @RequestBody PlaceOrderRequest request) {
    String id = commandBus.send(new PlaceOrder(request.customerId(), lines(request)));
    // Reading back keeps the 201 body identical in shape to GET. In a sample with a projection this
    // is where "read your own write" would bite (S12); here both sides are one table.
    OrderView view = queryBus.ask(new FindOrder(id));
    return ResponseEntity.created(URI.create("/orders/" + id)).body(OrderResponse.of(view));
  }

  @PostMapping("/{id}/confirm")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @ApiResponse(responseCode = "409", description = "The order is not in a state that can be confirmed.")
  void confirm(@PathVariable String id) {
    commandBus.send(new ConfirmOrder(id));
  }

  @GetMapping("/{id}")
  OrderResponse find(@PathVariable String id) {
    return OrderResponse.of(queryBus.ask(new FindOrder(id)));
  }

  private static List<PlaceOrder.Line> lines(PlaceOrderRequest request) {
    return request.lines().stream()
        .map(line -> new PlaceOrder.Line(line.sku(), line.quantity()))
        .toList();
  }
}

package com.example.samples.s20.ordering.adapter;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.QueryBus;
import com.aipersimmon.ddd.cqrs.page.Cursor;
import com.aipersimmon.ddd.cqrs.page.Slice;
import com.example.samples.s20.ordering.application.BrowseOrders;
import com.example.samples.s20.ordering.application.ConfirmOrder;
import com.example.samples.s20.ordering.application.OrderFilter;
import com.example.samples.s20.ordering.application.OrderSort;
import com.example.samples.s20.ordering.application.OrderSummary;
import com.example.samples.s20.ordering.application.PageRequest;
import com.example.samples.s20.ordering.application.PlaceOrder;
import com.example.samples.s20.ordering.domain.OrderStatus;
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

/**
 * The list endpoint, and the two commands that give it something to list.
 *
 * <p>The adapter's whole job on a read is binding: turn query parameters into the application's
 * values and return what comes back. It computes no page numbers, holds no default it did not get
 * from the contract, and never sees inside a cursor.
 *
 * <p>{@code sort} binds to an enum, which is the entire sort whitelist — an unknown value is refused
 * by the framework's type conversion before a query exists, so no column name a client chose can
 * reach a statement. {@code status} binds the same way.
 *
 * <p>The response is the {@code Slice} itself. The library's paging package calls this "the 'list
 * envelope' (a pagination shell), not a generic success envelope" — a list needs somewhere to put
 * {@code nextCursor}, and a single resource is still returned directly (S1).
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
    String id = commandBus.send(new PlaceOrder(request.customerId(), request.quantity()));
    return ResponseEntity.created(URI.create("/orders/" + id)).body(Map.of("id", id));
  }

  @PostMapping("/{id}/confirmation")
  ResponseEntity<Void> confirm(@PathVariable String id) {
    commandBus.send(new ConfirmOrder(id));
    return ResponseEntity.noContent().build();
  }

  @GetMapping
  Slice<OrderSummary> browse(
      @RequestParam(required = false) String customerId,
      @RequestParam(required = false) OrderStatus status,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "NEWEST_FIRST") OrderSort sort,
      @RequestParam(defaultValue = "" + PageRequest.DEFAULT_SIZE) int size) {
    return queryBus.ask(
        new BrowseOrders(
            new PageRequest(new OrderFilter(customerId, status), sort, size, cursorOf(cursor))));
  }

  /** {@code ?cursor=} with nothing after it is an absent cursor, not a blank one. */
  static Cursor cursorOf(String value) {
    return value == null || value.isBlank() ? null : Cursor.of(value);
  }

  record PlaceOrderRequest(@NotBlank String customerId, @Positive int quantity) {}
}

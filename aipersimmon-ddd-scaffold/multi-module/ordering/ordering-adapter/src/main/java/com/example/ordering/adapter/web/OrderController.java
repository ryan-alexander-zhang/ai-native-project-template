package com.example.ordering.adapter.web;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.QueryBus;
import com.example.ordering.application.order.ApproveReview;
import com.example.ordering.application.order.FindOrder;
import com.example.ordering.application.order.OrderSnapshot;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for placing, approving-review of, and reading orders. Writes go through the {@link
 * CommandBus} and reads through the {@link QueryBus}, so the adapter holds no orchestration itself.
 *
 * <p>There is deliberately no public {@code confirm} endpoint: confirming an order is an internal
 * step of the fulfilment saga (dispatched only once payment is authorized), not a client action, so
 * exposing it would let a caller bypass the saga's preconditions. Approving a held review, by
 * contrast, <em>is</em> a legitimate operator action and has its own endpoint below.
 */
@RestController
@RequestMapping("/orders")
@Tag(name = "Orders", description = "Place, approve-review, and read orders")
public class OrderController {

  private final CommandBus commandBus;
  private final QueryBus queryBus;

  public OrderController(CommandBus commandBus, QueryBus queryBus) {
    this.commandBus = commandBus;
    this.queryBus = queryBus;
  }

  // The 201 + Location shape is what the method actually returns but reflection over
  // ResponseEntity<Void> cannot infer; the error responses (400/404/429/500) come from the
  // starter's default problem family, so they are not repeated here.
  @Operation(summary = "Place a new order")
  @ApiResponse(
      responseCode = "201",
      description = "Order placed; its URI is in the Location header.",
      headers = @Header(name = "Location", description = "URI of the newly created order"))
  @PostMapping
  public ResponseEntity<Void> place(@Valid @RequestBody PlaceOrderRequest request) {
    String id = commandBus.send(request.toCommand());
    return ResponseEntity.created(URI.create("/orders/" + id)).build();
  }

  @Operation(summary = "Approve the manual review of an order awaiting it")
  @ApiResponse(
      responseCode = "204",
      description = "Review approved; the order is cleared for fulfilment.")
  @PostMapping("/{id}/approve-review")
  public ResponseEntity<Void> approveReview(
      @Parameter(
              description = "Identifier of the order whose review to approve.",
              example = "ord-123")
          @PathVariable
          String id) {
    commandBus.send(new ApproveReview(id));
    return ResponseEntity.noContent().build();
  }

  // The 200 body is the OrderSnapshot read model, a presentation-facing projection that documents
  // itself with @Schema; springdoc reflects it for this response. 400/404/429/500 come from the
  // default problem family.
  @Operation(summary = "Fetch an order by id")
  @ApiResponse(responseCode = "200", description = "The current snapshot of the order.")
  @GetMapping("/{id}")
  public ResponseEntity<OrderSnapshot> get(
      @Parameter(description = "Identifier of the order to fetch.", example = "ord-123")
          @PathVariable
          String id) {
    Optional<OrderSnapshot> snapshot = queryBus.ask(new FindOrder(id));
    return snapshot.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
  }
}

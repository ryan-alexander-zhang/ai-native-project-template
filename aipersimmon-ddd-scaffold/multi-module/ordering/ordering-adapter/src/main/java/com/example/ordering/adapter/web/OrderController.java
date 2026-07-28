package com.example.ordering.adapter.web;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.QueryBus;
import com.aipersimmon.ddd.cqrs.page.Cursor;
import com.aipersimmon.ddd.cqrs.page.Slice;
import com.example.ordering.application.order.ApproveReview;
import com.example.ordering.application.order.CancelOwnOrder;
import com.example.ordering.application.order.FindCustomerOrders;
import com.example.ordering.application.order.FindOrder;
import com.example.ordering.application.order.OrderListItem;
import com.example.ordering.application.order.OrderSnapshot;
import com.example.ordering.application.order.RejectReview;
import com.example.ordering.application.order.ShipOrder;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for placing, approving-review of, and reading orders. Writes go through the {@link
 * CommandBus} and reads through the {@link QueryBus}, so the adapter holds no orchestration itself.
 *
 * <p>There is deliberately no public {@code confirm} endpoint: confirming an order is an internal
 * step of the fulfilment process manager (dispatched only once payment is authorized), not a client
 * action, so exposing it would let a caller bypass the process manager's preconditions. Approving a
 * held review, by contrast, <em>is</em> a legitimate operator action and has its own endpoint
 * below.
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
              example = "0197c1e2-0a3b-7c4d-8e5f-6a7b8c9d0e1f")
          @PathVariable
          String id) {
    commandBus.send(new ApproveReview(id));
    return ResponseEntity.noContent().build();
  }

  // The other half of a review. Approval had an endpoint and refusal did not, so an order held for
  // review could only ever be let through — the domain modelled the refusal (ReviewRejected, its
  // own cancellation category, its own policy branch) and nothing could reach it (issue-00082).
  @Operation(summary = "Reject the manual review of an order awaiting it")
  @ApiResponse(responseCode = "204", description = "Review rejected; the order is cancelled.")
  @PostMapping("/{id}/reject-review")
  public ResponseEntity<Void> rejectReview(
      @Parameter(
              description = "Identifier of the order whose review to reject.",
              example = "0197c1e2-0a3b-7c4d-8e5f-6a7b8c9d0e1f")
          @PathVariable
          String id) {
    commandBus.send(new RejectReview(id));
    return ResponseEntity.noContent().build();
  }

  @Operation(summary = "Dispatch a confirmed order")
  @ApiResponse(responseCode = "204", description = "Shipped; the order is complete.")
  @PostMapping("/{id}/ship")
  public ResponseEntity<Void> ship(
      @Parameter(description = "Identifier of the confirmed order to dispatch.") @PathVariable
          String id) {
    commandBus.send(new ShipOrder(id));
    return ResponseEntity.noContent().build();
  }

  // The customer's own cancellation, as opposed to the compensating one the fulfilment process
  // manager issues with evidence. Whether it is still allowed is answered in advance on the order
  // snapshot (cancellableByCustomer), so a client can offer or hide the action; this endpoint is
  // where the aggregate decides for real, and refuses with the reason if the window has closed or
  // the caller does not own the order.
  @Operation(summary = "Cancel your own order, while it may still be cancelled")
  @ApiResponse(responseCode = "204", description = "Cancelled.")
  @PostMapping("/{id}/cancel")
  public ResponseEntity<Void> cancel(
      @Parameter(description = "Identifier of the order to cancel.") @PathVariable String id,
      @Parameter(
              description =
                  "The requesting customer. A real deployment takes this from the authenticated"
                      + " principal, never from the client.",
              example = "CUST-1")
          @RequestParam
          String customerId) {
    commandBus.send(new CancelOwnOrder(id, customerId));
    return ResponseEntity.noContent().build();
  }

  // Cursor paging, not page numbers: nextCursor is opaque and the client echoes it back verbatim,
  // which is what lets the server change how positions are encoded without breaking callers. A
  // response with no nextCursor is the last page.
  @Operation(summary = "List a customer's orders, newest first")
  @ApiResponse(responseCode = "200", description = "A page of the customer's orders.")
  @GetMapping
  public Slice<OrderListItem> list(
      @Parameter(description = "Customer whose orders to list.", example = "CUST-1") @RequestParam
          String customerId,
      @Parameter(description = "Cursor from the previous page; omit for the first page.")
          @RequestParam(required = false)
          String cursor,
      @Parameter(description = "Page size; clamped by the handler.", example = "20")
          @RequestParam(defaultValue = "20")
          int size) {
    return queryBus.ask(
        new FindCustomerOrders(customerId, cursor == null ? null : Cursor.of(cursor), size));
  }

  // The 200 body is the OrderSnapshot read model, a presentation-facing projection that documents
  // itself with @Schema; springdoc reflects it for this response. 400/404/429/500 come from the
  // default problem family.
  @Operation(summary = "Fetch an order by id")
  @ApiResponse(responseCode = "200", description = "The current snapshot of the order.")
  @GetMapping("/{id}")
  public ResponseEntity<OrderSnapshot> get(
      @Parameter(
              description = "Identifier of the order to fetch.",
              example = "0197c1e2-0a3b-7c4d-8e5f-6a7b8c9d0e1f")
          @PathVariable
          String id) {
    Optional<OrderSnapshot> snapshot = queryBus.ask(new FindOrder(id));
    return snapshot.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
  }
}

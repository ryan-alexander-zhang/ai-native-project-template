package com.example.ordering.adapter.web;

import com.example.ordering.application.order.ConfirmOrderService;
import com.example.ordering.application.order.FindOrderService;
import com.example.ordering.application.order.OrderSnapshot;
import com.example.ordering.application.order.PlaceOrderService;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST endpoints for placing, confirming, and reading orders. */
@RestController
@RequestMapping("/orders")
public class OrderController {

    private final PlaceOrderService placeOrder;
    private final ConfirmOrderService confirmOrder;
    private final FindOrderService findOrder;

    public OrderController(PlaceOrderService placeOrder, ConfirmOrderService confirmOrder,
                           FindOrderService findOrder) {
        this.placeOrder = placeOrder;
        this.confirmOrder = confirmOrder;
        this.findOrder = findOrder;
    }

    @PostMapping
    public ResponseEntity<Void> place(@RequestBody PlaceOrderRequest request) {
        String id = placeOrder.handle(request.toCommand());
        return ResponseEntity.created(URI.create("/orders/" + id)).build();
    }

    @PostMapping("/{id}/confirm")
    public ResponseEntity<Void> confirm(@PathVariable String id) {
        confirmOrder.confirm(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderSnapshot> get(@PathVariable String id) {
        return findOrder.byId(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}

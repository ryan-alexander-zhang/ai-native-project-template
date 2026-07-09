package com.acme.samples.s1.ordering.web;

import com.acme.samples.s1.ordering.application.order.PlaceOrderService;
import com.acme.samples.s1.ordering.domain.order.Order;
import com.acme.samples.s1.ordering.domain.order.Orders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.NoSuchElementException;

/** Inbound HTTP adapter for the Ordering context. */
@RestController
@RequestMapping("/orders")
public class OrderController {

    private final PlaceOrderService placeOrder;
    private final Orders orders;

    public OrderController(PlaceOrderService placeOrder, Orders orders) {
        this.placeOrder = placeOrder;
        this.orders = orders;
    }

    @PostMapping
    public ResponseEntity<OrderView> place(@RequestBody PlaceOrderRequest request) {
        String id = placeOrder.place(new PlaceOrderService.PlaceOrder(
                request.customerId(),
                request.lines().stream()
                        .map(l -> new PlaceOrderService.PlaceOrder.Line(l.sku(), l.qty()))
                        .toList()));
        return ResponseEntity.status(HttpStatus.CREATED).body(view(load(id)));
    }

    @GetMapping("/{id}")
    public OrderView get(@PathVariable String id) {
        return view(load(id));
    }

    private Order load(String id) {
        return orders.byId(id).orElseThrow(() -> new NoSuchElementException("order not found: " + id));
    }

    private OrderView view(Order o) {
        return new OrderView(o.id(), o.status().name(), o.total().amountMinor(), o.total().currency());
    }
}

package com.acme.samples.s2.ordering.adapter.web;

import com.acme.samples.s2.ordering.application.order.FindOrderService;
import com.acme.samples.s2.ordering.application.order.FindOrderService.OrderSnapshot;
import com.acme.samples.s2.ordering.application.order.PlaceOrderService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.NoSuchElementException;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final PlaceOrderService placeOrder;
    private final FindOrderService findOrder;

    public OrderController(PlaceOrderService placeOrder, FindOrderService findOrder) {
        this.placeOrder = placeOrder;
        this.findOrder = findOrder;
    }

    @PostMapping
    public ResponseEntity<OrderSnapshot> place(@RequestBody PlaceOrderRequest request) {
        String id = placeOrder.place(new PlaceOrderService.PlaceOrder(
                request.customerId(),
                request.lines().stream()
                        .map(l -> new PlaceOrderService.PlaceOrder.Line(l.sku(), l.qty()))
                        .toList()));
        return ResponseEntity.status(HttpStatus.CREATED).body(get(id));
    }

    @GetMapping("/{id}")
    public OrderSnapshot get(@PathVariable String id) {
        return findOrder.byId(id).orElseThrow(() -> new NoSuchElementException("order not found: " + id));
    }
}

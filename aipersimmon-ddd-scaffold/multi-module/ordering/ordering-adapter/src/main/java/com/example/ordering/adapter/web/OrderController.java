package com.example.ordering.adapter.web;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.QueryBus;
import com.example.ordering.application.order.ConfirmOrder;
import com.example.ordering.application.order.FindOrder;
import com.example.ordering.application.order.OrderSnapshot;
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
 * REST endpoints for placing, confirming, and reading orders. Writes go through the
 * {@link CommandBus} and reads through the {@link QueryBus}, so the adapter holds no
 * orchestration itself.
 */
@RestController
@RequestMapping("/orders")
public class OrderController {

    private final CommandBus commandBus;
    private final QueryBus queryBus;

    public OrderController(CommandBus commandBus, QueryBus queryBus) {
        this.commandBus = commandBus;
        this.queryBus = queryBus;
    }

    @PostMapping
    public ResponseEntity<Void> place(@Valid @RequestBody PlaceOrderRequest request) {
        String id = commandBus.send(request.toCommand());
        return ResponseEntity.created(URI.create("/orders/" + id)).build();
    }

    @PostMapping("/{id}/confirm")
    public ResponseEntity<Void> confirm(@PathVariable String id) {
        commandBus.send(new ConfirmOrder(id));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderSnapshot> get(@PathVariable String id) {
        Optional<OrderSnapshot> snapshot = queryBus.ask(new FindOrder(id));
        return snapshot.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}

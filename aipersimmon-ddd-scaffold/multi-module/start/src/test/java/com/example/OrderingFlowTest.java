package com.example;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.QueryBus;
import com.example.ordering.application.order.FindOrder;
import com.example.ordering.application.order.OrderSnapshot;
import com.example.ordering.application.order.PlaceOrder;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * End-to-end across both bounded contexts, driven through the CQRS buses and the
 * order-fulfilment orchestration saga. Sending a {@code PlaceOrder} command starts
 * the saga and announces the order; inventory reacts (via its own {@code ReserveStock}
 * command) and reports the outcome as an integration event; the saga then sends a
 * {@code ConfirmOrder} or {@code CancelOrder} command. Reads go through the query
 * bus. All synchronous, no broker.
 */
@SpringBootTest
class OrderingFlowTest {

    @Autowired
    CommandBus commandBus;

    @Autowired
    QueryBus queryBus;

    @Test
    void placingAnOrderReservesStockAndTheSagaConfirmsTheOrder() {
        String orderId = commandBus.send(new PlaceOrder(
                "CUST-1",
                List.of(new PlaceOrder.Line("SKU-1", 2, 100, "USD"))));

        OrderSnapshot snapshot = queryBus.ask(new FindOrder(orderId)).orElseThrow();
        assertEquals("CONFIRMED", snapshot.status());
    }

    @Test
    void whenStockCannotBeReservedTheSagaCompensatesByCancellingTheOrder() {
        String orderId = commandBus.send(new PlaceOrder(
                "CUST-1",
                List.of(new PlaceOrder.Line("SKU-404", 1, 100, "USD"))));

        OrderSnapshot snapshot = queryBus.ask(new FindOrder(orderId)).orElseThrow();
        assertEquals("CANCELLED", snapshot.status());
    }
}

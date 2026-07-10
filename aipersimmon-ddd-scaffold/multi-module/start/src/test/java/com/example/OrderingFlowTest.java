package com.example;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.ordering.application.order.FindOrderService;
import com.example.ordering.application.order.OrderSnapshot;
import com.example.ordering.application.order.PlaceOrderCommand;
import com.example.ordering.application.order.PlaceOrderService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * End-to-end across both bounded contexts, driven by the order-fulfilment
 * orchestration saga. Placing an order starts the saga and announces the order;
 * inventory reacts and reports the outcome as an integration event; the saga then
 * either confirms the order (stock reserved) or cancels it (reservation failed).
 * All synchronous, no broker.
 */
@SpringBootTest
class OrderingFlowTest {

    @Autowired
    PlaceOrderService placeOrder;

    @Autowired
    FindOrderService findOrder;

    @Test
    void placingAnOrderReservesStockAndTheSagaConfirmsTheOrder() {
        String orderId = placeOrder.handle(new PlaceOrderCommand(
                "CUST-1",
                List.of(new PlaceOrderCommand.Line("SKU-1", 2, 100, "USD"))));

        OrderSnapshot snapshot = findOrder.byId(orderId).orElseThrow();
        assertEquals("CONFIRMED", snapshot.status());
    }

    @Test
    void whenStockCannotBeReservedTheSagaCompensatesByCancellingTheOrder() {
        String orderId = placeOrder.handle(new PlaceOrderCommand(
                "CUST-1",
                List.of(new PlaceOrderCommand.Line("SKU-404", 1, 100, "USD"))));

        OrderSnapshot snapshot = findOrder.byId(orderId).orElseThrow();
        assertEquals("CANCELLED", snapshot.status());
    }
}

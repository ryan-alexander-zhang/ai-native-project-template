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
 * End-to-end across both bounded contexts: placing an order publishes an
 * integration event that inventory reserves stock for and acknowledges in
 * process, which in turn confirms the order. All synchronous, no broker.
 */
@SpringBootTest
class OrderingFlowTest {

    @Autowired
    PlaceOrderService placeOrder;

    @Autowired
    FindOrderService findOrder;

    @Test
    void placingAnOrderReservesStockAndConfirmsTheOrder() {
        String orderId = placeOrder.handle(new PlaceOrderCommand(
                "CUST-1",
                List.of(new PlaceOrderCommand.Line("SKU-1", 2, 100, "USD"))));

        OrderSnapshot snapshot = findOrder.byId(orderId).orElseThrow();
        assertEquals("CONFIRMED", snapshot.status());
    }
}

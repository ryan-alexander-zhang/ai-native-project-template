package com.example.howto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aipersimmon.ddd.saga.SagaStatus;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves the deadline scheduler is wired and actually fires: with a very short
 * confirmation timeout, placing an order and never reserving stock leaves the saga
 * to be compensated by the scheduler on its own. Polls for the resulting state so
 * the test does not depend on exact timing.
 */
@SpringBootTest(properties = "howto.saga.confirm-timeout-ms=200")
class SagaDeadlineFiresHowToTest {

    @Autowired
    OrderFulfilment fulfilment;
    @Autowired
    OrderFulfilmentSagaStore store;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM aipersimmon_saga");
    }

    @Test
    void theScheduledDeadlineFiresAndCompensates() throws InterruptedException {
        fulfilment.onOrderPlaced("order-1", "SKU-1");

        assertEquals(SagaStatus.ABORTED, awaitStatus("order-1", 3000));
    }

    private SagaStatus awaitStatus(String orderId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Optional<OrderFulfilmentSaga> saga = store.find(orderId);
            if (saga.isPresent() && saga.get().status().isTerminal()) {
                return saga.get().status();
            }
            Thread.sleep(50);
        }
        return store.find(orderId).map(OrderFulfilmentSaga::status).orElse(null);
    }
}

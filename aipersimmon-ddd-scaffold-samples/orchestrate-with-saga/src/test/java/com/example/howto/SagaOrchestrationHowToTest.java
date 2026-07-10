package com.example.howto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aipersimmon.ddd.saga.Deadline;
import com.aipersimmon.ddd.saga.SagaStatus;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Drives the saga's branches directly (the deadline is invoked by hand so the test
 * is deterministic): completing when stock is reserved, compensating when the
 * confirmation deadline fires, and ignoring a deadline that arrives after the flow
 * has already completed. The default confirmation timeout is long, so the real
 * scheduled deadline never fires during these tests.
 */
@SpringBootTest
class SagaOrchestrationHowToTest {

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
    void completesWhenStockIsReservedInTime() {
        fulfilment.onOrderPlaced("order-1", "SKU-1");
        assertEquals(SagaStatus.RUNNING, statusOf("order-1"));

        fulfilment.onStockReserved("order-1");
        assertEquals(SagaStatus.COMPLETED, statusOf("order-1"));
    }

    @Test
    void compensatesWhenTheConfirmationDeadlineFires() {
        fulfilment.onOrderPlaced("order-2", "SKU-2");

        fulfilment.onDeadline(new Deadline("order-2", OrderFulfilment.CONFIRM_TIMEOUT, Instant.now()));

        assertEquals(SagaStatus.ABORTED, statusOf("order-2"));
    }

    @Test
    void ignoresADeadlineThatArrivesAfterCompletion() {
        fulfilment.onOrderPlaced("order-3", "SKU-3");
        fulfilment.onStockReserved("order-3");

        // A late timeout for an already-completed flow is a no-op.
        fulfilment.onDeadline(new Deadline("order-3", OrderFulfilment.CONFIRM_TIMEOUT, Instant.now()));

        assertEquals(SagaStatus.COMPLETED, statusOf("order-3"));
    }

    private SagaStatus statusOf(String orderId) {
        return store.find(orderId).orElseThrow().status();
    }
}

package com.example.howto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Drives the combined flow: the saga sends commands through the bus, whose handlers
 * publish integration events reliably through the outbox; the relay redelivers them
 * in process to advance the saga.
 *
 * <ul>
 *   <li>Happy path: reserve succeeds → the saga confirms the order (async via the relay).</li>
 *   <li>Compensation: reserve fails → the saga cancels the order.</li>
 *   <li>Reliable outbound: a handler failure rolls back both the write and the
 *       outbox row — nothing is emitted, no dual-write.</li>
 * </ul>
 */
@SpringBootTest
class SagaCommandsAndOutboxHowToTest {

    @Autowired
    OrderService orderService;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM orders");
        jdbc.update("DELETE FROM aipersimmon_outbox");
        jdbc.update("UPDATE stock SET available = 10");
    }

    @Test
    void reserveSucceedsAndTheSagaConfirmsTheOrder() {
        orderService.placeOrder("order-1", "SKU-1");

        assertEquals("CONFIRMED", awaitStatus("order-1"));
        assertEquals(9, available("SKU-1"));
    }

    @Test
    void reserveFailsAndTheSagaCancelsTheOrder() {
        orderService.placeOrder("order-2", "SKU-UNKNOWN");

        assertEquals("CANCELLED", awaitStatus("order-2"));
    }

    @Test
    void aHandlerFailureRollsBackBothTheWriteAndTheOutboxRow() {
        assertThrows(RuntimeException.class, () -> orderService.placeOrder("boom-1", "BOOM-SKU"));

        // The order row, the stock decrement, and the outbox row all rolled back.
        assertEquals(0, count("SELECT COUNT(*) FROM orders WHERE id = 'boom-1'"));
        assertEquals(10, available("BOOM-SKU"));
        assertEquals(0, count("SELECT COUNT(*) FROM aipersimmon_outbox"));
    }

    private String awaitStatus(String orderId) {
        long deadline = System.currentTimeMillis() + 10_000;
        String status = null;
        while (System.currentTimeMillis() < deadline) {
            status = jdbc.query("SELECT status FROM orders WHERE id = ?",
                    rs -> rs.next() ? rs.getString(1) : null, orderId);
            if ("CONFIRMED".equals(status) || "CANCELLED".equals(status)) {
                return status;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return status;
    }

    private int available(String sku) {
        return jdbc.queryForObject("SELECT available FROM stock WHERE sku = ?", Integer.class, sku);
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}

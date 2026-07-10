package com.example.howto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.QueryBus;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Drives the CQRS how-to: a command flows through the bus chain, the read model is
 * updated from the domain event in the same transaction and read back via the
 * query bus; validation rejects a bad command before any write; and a handler
 * failure rolls back both the write model and the read model.
 */
@SpringBootTest
class AddCqrsReadModelHowToTest {

    @Autowired
    CommandBus commandBus;
    @Autowired
    QueryBus queryBus;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM orders");
        jdbc.update("DELETE FROM order_summary");
    }

    @Test
    void placingAnOrderUpdatesTheReadModelAndIsQueryable() {
        String id = commandBus.send(new PlaceOrder("order-1", "SKU-1"));
        assertEquals("order-1", id);

        // Write model persisted...
        assertEquals(1, count("orders"));
        // ...and the read model updated from the domain event, in the same transaction.
        OrderSummary summary = queryBus.ask(new FindOrderSummary("order-1"));
        assertEquals("SKU-1", summary.sku());
        assertEquals("PLACED", summary.status());
    }

    @Test
    void validationRejectsABlankCommandBeforeAnyWrite() {
        assertThrows(ConstraintViolationException.class,
                () -> commandBus.send(new PlaceOrder("order-2", "  ")));

        assertEquals(0, count("orders"));
        assertEquals(0, count("order_summary"));
    }

    @Test
    void handlerFailureRollsBackBothModels() {
        assertThrows(RuntimeException.class,
                () -> commandBus.send(new PlaceOrder("order-3", "BOOM")));

        assertEquals(0, count("orders"));
        assertEquals(0, count("order_summary"));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}

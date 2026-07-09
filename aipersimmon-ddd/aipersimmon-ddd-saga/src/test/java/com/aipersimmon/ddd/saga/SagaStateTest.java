package com.aipersimmon.ddd.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Verifies the correlation-id guard and the legal saga lifecycle transitions. */
class SagaStateTest {

    /** A trivial concrete saga exposing the protected transitions for the test. */
    static final class OrderFulfilment extends SagaState {
        OrderFulfilment(String correlationId) {
            super(correlationId);
        }

        OrderFulfilment(String correlationId, SagaStatus status, long version) {
            super(correlationId, status, version);
        }

        void compensate() {
            startCompensation();
        }

        void finish() {
            complete();
        }

        void give() {
            abort();
        }
    }

    @Test
    void startsRunningActiveAndUnversioned() {
        OrderFulfilment saga = new OrderFulfilment("order-1");

        assertEquals("order-1", saga.correlationId());
        assertEquals(SagaStatus.RUNNING, saga.status());
        assertEquals(0L, saga.version());
        assertTrue(saga.isActive());
    }

    @Test
    void rejectsBlankCorrelationId() {
        assertThrows(IllegalArgumentException.class, () -> new OrderFulfilment(" "));
    }

    @Test
    void completesFromRunning() {
        OrderFulfilment saga = new OrderFulfilment("order-1");

        saga.finish();

        assertEquals(SagaStatus.COMPLETED, saga.status());
        assertFalse(saga.isActive());
    }

    @Test
    void compensatesThenAborts() {
        OrderFulfilment saga = new OrderFulfilment("order-1");

        saga.compensate();
        assertEquals(SagaStatus.COMPENSATING, saga.status());
        assertTrue(saga.isActive());

        saga.give();
        assertEquals(SagaStatus.ABORTED, saga.status());
        assertFalse(saga.isActive());
    }

    @Test
    void rejectsTransitionOutOfTerminalStatus() {
        OrderFulfilment saga = new OrderFulfilment("order-1");
        saga.finish();

        assertThrows(IllegalStateException.class, saga::finish);
        assertThrows(IllegalStateException.class, saga::compensate);
        assertThrows(IllegalStateException.class, saga::give);
    }

    @Test
    void rehydratesWithPersistedStatus() {
        OrderFulfilment saga = new OrderFulfilment("order-1", SagaStatus.COMPENSATING, 3L);

        assertEquals(SagaStatus.COMPENSATING, saga.status());
        assertEquals(3L, saga.version());
        // From COMPENSATING, aborting is legal but completing is not.
        assertThrows(IllegalStateException.class, saga::finish);
        saga.give();
        assertEquals(SagaStatus.ABORTED, saga.status());
    }
}

package com.aipersimmon.ddd.saga.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.saga.SagaState;
import com.aipersimmon.ddd.saga.SagaStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies the abstract JDBC saga store: a new saga inserts at version 1, an
 * advance bumps the version and persists the new status, and a save against a
 * stale version is rejected with an optimistic-locking failure — the guard that
 * stops two events advancing the same saga at once.
 */
@SpringBootTest
class JdbcSagaStoreTest {

    @Autowired
    ReservationSagaStore store;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM aipersimmon_saga");
    }

    @Test
    void savesNewSagaThenFindsItAtVersionOne() {
        store.save(new ReservationSaga("order-1", "SKU-1"));

        ReservationSaga loaded = store.find("order-1").orElseThrow();
        assertEquals("order-1", loaded.correlationId());
        assertEquals("SKU-1", loaded.sku());
        assertEquals(SagaStatus.RUNNING, loaded.status());
        assertEquals(1L, loaded.version());
    }

    @Test
    void advanceBumpsVersionAndPersistsStatus() {
        store.save(new ReservationSaga("order-1", "SKU-1"));

        ReservationSaga loaded = store.find("order-1").orElseThrow();
        loaded.finish();
        store.save(loaded);

        ReservationSaga reloaded = store.find("order-1").orElseThrow();
        assertEquals(SagaStatus.COMPLETED, reloaded.status());
        assertEquals(2L, reloaded.version());
    }

    @Test
    void staleSaveIsRejectedWithOptimisticLockingFailure() {
        store.save(new ReservationSaga("order-1", "SKU-1"));

        // Two readers load the same version-1 instance.
        ReservationSaga first = store.find("order-1").orElseThrow();
        ReservationSaga second = store.find("order-1").orElseThrow();

        // The first advance wins.
        first.finish();
        store.save(first);

        // The second still holds version 1, which no longer matches the stored row.
        second.finish();
        OptimisticLockingFailureException failure =
                assertThrows(OptimisticLockingFailureException.class, () -> store.save(second));
        assertTrue(failure.getMessage().contains("order-1"));
    }

    // --- test fixtures -----------------------------------------------------

    /** A saga whose only flow datum is the sku being reserved. */
    static final class ReservationSaga extends SagaState {
        private final String sku;

        ReservationSaga(String correlationId, String sku) {
            super(correlationId);
            this.sku = sku;
        }

        ReservationSaga(String correlationId, SagaStatus status, long version, String sku) {
            super(correlationId, status, version);
            this.sku = sku;
        }

        String sku() {
            return sku;
        }

        void finish() {
            complete();
        }
    }

    static final class ReservationSagaStore extends JdbcSagaStore<ReservationSaga> {
        ReservationSagaStore(JdbcTemplate jdbc) {
            super(jdbc);
        }

        @Override
        protected ReservationSaga mapRow(String correlationId, SagaStatus status, long version, String data) {
            return new ReservationSaga(correlationId, status, version, data);
        }

        @Override
        protected String serializeData(ReservationSaga saga) {
            return saga.sku();
        }
    }

    @Configuration
    @EnableAutoConfiguration
    static class TestApp {
        @Bean
        ReservationSagaStore reservationSagaStore(JdbcTemplate jdbc) {
            return new ReservationSagaStore(jdbc);
        }
    }
}

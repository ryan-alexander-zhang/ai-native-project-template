package com.example.howto;

import com.aipersimmon.ddd.saga.SagaStatus;
import com.aipersimmon.ddd.saga.spring.JdbcSagaStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persists {@link OrderFulfilmentSaga} instances. The base class owns the lookup,
 * insert, and version-checked update; this subclass supplies only the mapping
 * between the saga's flow data and the stored {@code data} column (here just the
 * sku).
 */
@Repository
public class OrderFulfilmentSagaStore extends JdbcSagaStore<OrderFulfilmentSaga> {

    public OrderFulfilmentSagaStore(JdbcTemplate jdbc) {
        super(jdbc);
    }

    @Override
    protected OrderFulfilmentSaga mapRow(String correlationId, SagaStatus status, long version, String data) {
        return new OrderFulfilmentSaga(correlationId, status, version, data);
    }

    @Override
    protected String serializeData(OrderFulfilmentSaga saga) {
        return saga.sku();
    }
}

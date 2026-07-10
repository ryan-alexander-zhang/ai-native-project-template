package com.example.ordering.infrastructure.persistence.fulfilment;

import com.aipersimmon.ddd.saga.SagaStore;
import com.example.ordering.application.fulfilment.OrderFulfilmentSaga;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * In-memory {@link SagaStore} for the order-fulfilment saga, matching this
 * reference project's in-memory repositories. It stores instances by correlation
 * id (the order id). Being single-process and in-memory, it does not enforce the
 * optimistic-lock version a durable store (for example a JDBC one) would; a
 * production project would swap in such a store without changing the coordinator.
 */
@Component
public class InMemoryOrderFulfilmentSagaStore implements SagaStore<OrderFulfilmentSaga> {

    private final Map<String, OrderFulfilmentSaga> store = new ConcurrentHashMap<>();

    @Override
    public Optional<OrderFulfilmentSaga> find(String correlationId) {
        return Optional.ofNullable(store.get(correlationId));
    }

    @Override
    public void save(OrderFulfilmentSaga saga) {
        store.put(saga.correlationId(), saga);
    }
}

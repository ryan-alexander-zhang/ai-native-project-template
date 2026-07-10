package com.example.howto;

import com.aipersimmon.ddd.saga.SagaStore;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** In-memory saga store, keyed by order id. A durable store would swap in here unchanged. */
@Component
public class InMemoryFulfilmentSagaStore implements SagaStore<FulfilmentSaga> {

    private final Map<String, FulfilmentSaga> store = new ConcurrentHashMap<>();

    @Override
    public Optional<FulfilmentSaga> find(String correlationId) {
        return Optional.ofNullable(store.get(correlationId));
    }

    @Override
    public void save(FulfilmentSaga saga) {
        store.put(saga.correlationId(), saga);
    }
}

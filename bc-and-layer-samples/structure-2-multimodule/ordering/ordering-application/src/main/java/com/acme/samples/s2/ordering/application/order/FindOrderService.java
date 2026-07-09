package com.acme.samples.s2.ordering.application.order;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Read-side use case (CQRS-lite): delegates to the read-model port, which queries
 * a projection view directly and bypasses the Order aggregate / write repository
 * (analysis-00005 §5).
 */
@Service
public class FindOrderService {

    private final OrderQueries orderQueries;

    public FindOrderService(OrderQueries orderQueries) { this.orderQueries = orderQueries; }

    @Transactional(readOnly = true)
    public Optional<OrderSnapshot> byId(String id) {
        return orderQueries.byId(id);
    }
}

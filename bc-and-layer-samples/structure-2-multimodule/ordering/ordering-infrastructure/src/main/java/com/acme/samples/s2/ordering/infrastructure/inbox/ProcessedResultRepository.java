package com.acme.samples.s2.ordering.infrastructure.inbox;

import com.acme.samples.s2.ordering.application.order.ProcessedResults;
import org.springframework.stereotype.Repository;

/** Inbox adapter backing {@link ProcessedResults} with a keyed table. */
@Repository
public class ProcessedResultRepository implements ProcessedResults {

    private final ProcessedResultMapper mapper;

    public ProcessedResultRepository(ProcessedResultMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean alreadyApplied(String orderId) {
        return mapper.selectById(orderId) != null;
    }

    @Override
    public void markApplied(String orderId) {
        ProcessedResultPo po = new ProcessedResultPo();
        po.setOrderId(orderId);
        mapper.insert(po);
    }
}

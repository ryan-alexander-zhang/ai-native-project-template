package com.example.samples.s27.customer.infrastructure;

import com.example.samples.s27.customer.application.OutboxQueue;
import org.springframework.stereotype.Repository;

/** One count. */
@Repository
class MyBatisOutboxQueue implements OutboxQueue {

  private final OutboxQueueMapper mapper;

  MyBatisOutboxQueue(OutboxQueueMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public long unsentFor(String subject) {
    return mapper.countUnsentFor(subject);
  }
}

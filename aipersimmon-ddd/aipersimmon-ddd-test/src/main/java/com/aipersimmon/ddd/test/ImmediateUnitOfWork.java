package com.aipersimmon.ddd.test;

import com.aipersimmon.ddd.cqrs.UnitOfWork;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * A {@link UnitOfWork} with no transaction: the work runs immediately on the calling thread, its
 * result and exceptions pass straight through. For unit tests of code that demands a unit of work
 * without wanting a transaction manager. The number of boundaries opened is counted, so a test can
 * assert "this ran inside exactly one unit of work" — often the whole point of the code under test.
 */
public final class ImmediateUnitOfWork implements UnitOfWork {

  private final AtomicInteger executions = new AtomicInteger();

  @Override
  public <R> R execute(Supplier<R> work) {
    executions.incrementAndGet();
    return work.get();
  }

  /** How many transactional boundaries were opened. */
  public int executions() {
    return executions.get();
  }
}

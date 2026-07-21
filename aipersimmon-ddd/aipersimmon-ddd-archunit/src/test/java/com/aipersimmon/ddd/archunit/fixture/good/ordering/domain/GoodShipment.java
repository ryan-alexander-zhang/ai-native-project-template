package com.aipersimmon.ddd.archunit.fixture.good.ordering.domain;

import com.aipersimmon.ddd.core.state.Transitions;

/**
 * A well-formed stateful domain object: it declares the legal transitions in a {@link Transitions}
 * table and validates every change through {@code check}, so the {@code
 * IllegalStateTransitionException} is raised only from inside {@code Transitions}. Exercises the
 * good path of {@code illegalStateTransitionsShouldOnlyComeFromTransitions}.
 */
public class GoodShipment {

  enum Status {
    PENDING,
    SHIPPED,
    DELIVERED
  }

  private static final Transitions<Status> RULES =
      Transitions.<Status>of()
          .allow(Status.PENDING, Status.SHIPPED)
          .allow(Status.SHIPPED, Status.DELIVERED);

  private Status status = Status.PENDING;

  public void ship() {
    RULES.check(status, Status.SHIPPED);
    this.status = Status.SHIPPED;
  }
}

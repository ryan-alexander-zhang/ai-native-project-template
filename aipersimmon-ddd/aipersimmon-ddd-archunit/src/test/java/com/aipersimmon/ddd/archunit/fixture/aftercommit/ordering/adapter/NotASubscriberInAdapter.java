package com.aipersimmon.ddd.archunit.fixture.aftercommit.ordering.adapter;

import com.aipersimmon.ddd.archunit.fixture.aftercommit.ordering.domain.AfterCommitOrderPlaced;

/**
 * The control for the widened predicate: a method taking a domain event, in the wrong layer, with
 * no subscription annotation at all — neither directly present nor meta-annotated. Broadening the
 * match to meta-annotations must not turn "takes a domain event as a parameter" into "subscribes to
 * it", so this class must stay out of every violation report.
 */
public class NotASubscriberInAdapter {

  public void render(AfterCommitOrderPlaced event) {
    // a plain method that happens to take an event — not a subscription
  }
}

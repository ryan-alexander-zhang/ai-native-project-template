package com.aipersimmon.ddd.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.core.event.DomainEvent;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DomainEventsTest {

  private record SampleEvent(String what) implements DomainEvent {}

  /** A sink that records what was published, so delegation is observable. */
  private static final class Collecting implements DomainEvents {
    final List<DomainEvent> published = new ArrayList<>();

    @Override
    public void publish(DomainEvent event) {
      published.add(event);
    }
  }

  private static final class Aggregate extends AbstractAggregateRoot<String> {
    @Override
    public String id() {
      return "a-1";
    }

    void raise(DomainEvent event) {
      registerEvent(event);
    }
  }

  @Test
  void publishAll_publishesEachEventInOrder() {
    Collecting sink = new Collecting();
    SampleEvent first = new SampleEvent("a");
    SampleEvent second = new SampleEvent("b");

    sink.publishAll(List.of(first, second));

    assertEquals(List.of(first, second), sink.published);
  }

  @Test
  void publishAndClear_publishesTheAggregatesEventsThenClearsThem() {
    Collecting sink = new Collecting();
    Aggregate aggregate = new Aggregate();
    SampleEvent first = new SampleEvent("a");
    SampleEvent second = new SampleEvent("b");
    aggregate.raise(first);
    aggregate.raise(second);

    sink.publishAndClear(aggregate);

    assertEquals(List.of(first, second), sink.published);
    assertTrue(aggregate.domainEvents().isEmpty(), "events are drained after publishing");
  }
}

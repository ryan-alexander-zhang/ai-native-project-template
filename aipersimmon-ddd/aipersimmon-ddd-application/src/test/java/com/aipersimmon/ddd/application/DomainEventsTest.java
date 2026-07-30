package com.aipersimmon.ddd.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

  /** A sink whose listener reacts by touching an aggregate, the way a synchronous handler does. */
  private static final class Reacting implements DomainEvents {
    final List<DomainEvent> published = new ArrayList<>();
    Runnable onEach = () -> {};

    @Override
    public void publish(DomainEvent event) {
      published.add(event);
      onEach.run();
    }
  }

  @Test
  void aListenerThatTouchesAnotherAggregateDoesNotBreakPublication() {
    Reacting sink = new Reacting();
    Aggregate aggregate = new Aggregate();
    Aggregate other = new Aggregate();
    aggregate.raise(new SampleEvent("a"));
    aggregate.raise(new SampleEvent("b"));
    sink.onEach = () -> other.raise(new SampleEvent("elsewhere"));

    sink.publishAndClear(aggregate);

    // Iterating a live view used to throw ConcurrentModificationException here as soon as anything
    // recorded an event mid-publication. Reacting to an event by changing something else is the
    // ordinary case, not an abuse, so it has to work.
    assertEquals(2, sink.published.size());
    assertEquals(2, other.domainEvents().size());
  }

  @Test
  void aListenerThatRecordsOnTheSameAggregateIsRefusedRatherThanSilentlyDropped() {
    Reacting sink = new Reacting();
    Aggregate aggregate = new Aggregate();
    aggregate.raise(new SampleEvent("a"));
    sink.onEach = () -> aggregate.raise(new SampleEvent("too late"));

    IllegalStateException refused =
        assertThrows(IllegalStateException.class, () -> sink.publishAndClear(aggregate));

    // The root was already persisted when this ran, so the state that event announces was never
    // written. Publishing it would describe something that did not happen; clearing it away — which
    // is what a snapshot plus a blanket clear would have done — is how a domain event goes missing.
    assertTrue(refused.getMessage().contains("already persisted"), refused.getMessage());
    assertEquals(1, sink.published.size(), "the events that were real still went out");
  }

  @Test
  void publishAndClearOnAnAggregateWithNoEventsDoesNothing() {
    Reacting sink = new Reacting();

    sink.publishAndClear(new Aggregate());

    assertTrue(sink.published.isEmpty());
  }
}

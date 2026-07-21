package com.aipersimmon.ddd.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.core.event.DomainEvent;
import java.util.List;
import org.junit.jupiter.api.Test;

class AbstractAggregateRootTest {

  private record SampleEvent(String what) implements DomainEvent {}

  /** A minimal aggregate root that exposes the protected event-recording hook for testing. */
  private static final class Order extends AbstractAggregateRoot<String> {
    private final String id;

    Order(String id) {
      this.id = id;
    }

    @Override
    public String id() {
      return id;
    }

    void raise(DomainEvent event) {
      registerEvent(event);
    }
  }

  @Test
  void startsWithNoEvents() {
    assertTrue(new Order("o-1").domainEvents().isEmpty());
  }

  @Test
  void registerEvent_recordsEventsInOrder() {
    Order order = new Order("o-1");
    SampleEvent first = new SampleEvent("a");
    SampleEvent second = new SampleEvent("b");

    order.raise(first);
    order.raise(second);

    assertEquals(List.of(first, second), order.domainEvents());
  }

  @Test
  void domainEvents_returnsAnUnmodifiableSnapshot() {
    Order order = new Order("o-1");
    order.raise(new SampleEvent("a"));

    List<DomainEvent> events = order.domainEvents();

    assertThrows(UnsupportedOperationException.class, () -> events.add(new SampleEvent("x")));
  }

  @Test
  void clearDomainEvents_removesEveryRecordedEvent() {
    Order order = new Order("o-1");
    order.raise(new SampleEvent("a"));
    order.raise(new SampleEvent("b"));

    order.clearDomainEvents();

    assertTrue(order.domainEvents().isEmpty());
  }
}

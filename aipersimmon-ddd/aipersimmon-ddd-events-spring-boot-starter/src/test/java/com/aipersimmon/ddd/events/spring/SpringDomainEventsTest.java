package com.aipersimmon.ddd.events.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.core.event.DomainEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class SpringDomainEventsTest {

  record SampleEvent(String id) implements DomainEvent {}

  @Test
  void publishDelegatesToApplicationEventPublisher() {
    List<Object> captured = new ArrayList<>();
    ApplicationEventPublisher publisher = captured::add;
    DomainEvents events = new SpringDomainEvents(publisher);

    SampleEvent event = new SampleEvent("1");
    events.publish(event);

    assertEquals(List.of(event), captured);
  }

  @Test
  void publishAllDelegatesEachEvent() {
    List<Object> captured = new ArrayList<>();
    DomainEvents events = new SpringDomainEvents(captured::add);

    SampleEvent a = new SampleEvent("a");
    SampleEvent b = new SampleEvent("b");
    events.publishAll(List.of(a, b));

    assertEquals(List.of(a, b), captured);
  }
}

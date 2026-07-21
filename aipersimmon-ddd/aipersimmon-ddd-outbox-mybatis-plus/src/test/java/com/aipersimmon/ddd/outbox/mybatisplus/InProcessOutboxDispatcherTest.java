package com.aipersimmon.ddd.outbox.mybatisplus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.integration.EventEnvelope;
import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import com.aipersimmon.ddd.outbox.InProcessOutboxDispatcher;
import com.aipersimmon.ddd.outbox.OutboxDispatcher;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies mode 2 (in-process asynchronous) over MyBatis-Plus storage: with the in-process
 * dispatcher enabled, the relay reconstructs the stored event and republishes it to a local
 * {@code @EventListener}. Confirms the storage-agnostic dispatcher from the outbox core works over
 * the MyBatis-Plus backend too.
 */
@SpringBootTest(
    classes = InProcessOutboxDispatcherTest.TestApp.class,
    properties = {
      "aipersimmon.ddd.outbox.poll-delay-ms=3600000",
      "aipersimmon.ddd.outbox.dispatch=in-process"
    })
class InProcessOutboxDispatcherTest {

  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class TestApp {
    @Bean
    CapturingListener capturingListener() {
      return new CapturingListener();
    }
  }

  static class CapturingListener {
    final List<EventEnvelope<InProcessSampleEvent>> received = new CopyOnWriteArrayList<>();

    @EventListener
    void on(EventEnvelope<InProcessSampleEvent> envelope) {
      received.add(envelope);
    }
  }

  @EventType(name = "com.example.ordering.InProcessSample", version = 1)
  record InProcessSampleEvent(String orderId) implements IntegrationEvent {}

  @Autowired IntegrationEvents integrationEvents;
  @Autowired OutboxRelay relay;
  @Autowired OutboxDispatcher dispatcher;
  @Autowired JdbcTemplate jdbc;
  @Autowired CapturingListener listener;

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM aipersimmon_outbox");
    listener.received.clear();
  }

  @Test
  void relayRepublishesTheEventInProcess() {
    assertInstanceOf(InProcessOutboxDispatcher.class, dispatcher);

    integrationEvents.publish(new InProcessSampleEvent("O-1"), CommandContext.root("cmd-1"));
    relay.relay();

    assertEquals(1, listener.received.size());
    EventEnvelope<InProcessSampleEvent> envelope = listener.received.get(0);
    assertEquals("O-1", envelope.payload().orderId());
    assertEquals("cmd-1", envelope.correlationId(), "correlation survives the outbox round-trip");
  }
}

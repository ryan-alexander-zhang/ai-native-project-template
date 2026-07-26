package com.aipersimmon.ddd.outbox.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.core.id.IdGenerator;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import com.aipersimmon.ddd.tenancy.Tenants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The outbox writer mints a brand-new event's {@code event_id} from the {@link IdGenerator} bean
 * when one is present (in production, the UUIDv7 generator that improves locality on the {@code
 * event_id} unique index). A sentinel generator proves the id flows from the bean rather than an
 * inlined {@code UUID.randomUUID()}. The generator is required, not optional: {@code
 * aipersimmon-ddd-id} is a compile dependency of this module, so the other outbox tests get the
 * real UUIDv7 generator from auto-configuration rather than a random-UUID fallback (see {@code
 * issue-00053}).
 */
@SpringBootTest(
    classes = OutboxIdGeneratorWiringTest.TestApp.class,
    properties = "aipersimmon.ddd.outbox.poll-delay-ms=3600000")
class OutboxIdGeneratorWiringTest {

  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class TestApp {
    @Bean
    IdGenerator idGenerator() {
      return () -> "outbox-id-sentinel";
    }
  }

  @EventType(name = "com.example.ordering.IdGenSample", version = 1)
  record SampleEvent(String orderId) implements IntegrationEvent {}

  @Autowired IntegrationEvents integrationEvents;
  @Autowired JdbcTemplate jdbc;

  @Test
  void freshEventIdComesFromTheInjectedGenerator() {
    jdbc.update("DELETE FROM aipersimmon_outbox");

    integrationEvents.publish(
        new SampleEvent("O-1"), CommandContext.root(Tenants.ROOT.value(), "cmd-1"));

    assertEquals(
        "outbox-id-sentinel",
        jdbc.queryForObject("SELECT event_id FROM aipersimmon_outbox", String.class),
        "a brand-new event id is minted by the injected IdGenerator");
  }
}

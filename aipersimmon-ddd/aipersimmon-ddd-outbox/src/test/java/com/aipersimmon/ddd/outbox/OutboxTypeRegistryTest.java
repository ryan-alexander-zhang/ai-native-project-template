package com.aipersimmon.ddd.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import com.aipersimmon.ddd.integration.RegistryIntegrationEventCatalog.Key;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The scanner's per-class registration ({@link AipersimmonDddOutboxAutoConfiguration#register}): a
 * class is keyed by its required-annotation {@code (name, version)}; the same class scanned twice
 * is idempotent; the same name at a different version is allowed (that is how revisions coexist);
 * an unannotated event fails; and two classes claiming the same {@code (name, version)} fail fast
 * instead of one silently shadowing the other (which would deserialize into the wrong class).
 */
class OutboxTypeRegistryTest {

  @EventType(name = "com.example.OrderPlaced", version = 1)
  record OrderPlaced(String id) implements IntegrationEvent {}

  @EventType(name = "com.example.OrderPlaced", version = 1)
  record OrderPlacedClone(String id) implements IntegrationEvent {}

  @EventType(name = "com.example.OrderPlaced", version = 2)
  record OrderPlacedV2(String id) implements IntegrationEvent {}

  record Unannotated(String id) implements IntegrationEvent {}

  @Test
  void keysByTypeAndVersion() {
    Map<Key, Class<? extends IntegrationEvent>> byType = new HashMap<>();
    AipersimmonDddOutboxAutoConfiguration.register(byType, OrderPlaced.class);

    assertSame(OrderPlaced.class, byType.get(new Key("com.example.OrderPlaced", 1)));
  }

  @Test
  void sameNameDifferentVersionCoexists() {
    Map<Key, Class<? extends IntegrationEvent>> byType = new HashMap<>();
    AipersimmonDddOutboxAutoConfiguration.register(byType, OrderPlaced.class);
    AipersimmonDddOutboxAutoConfiguration.register(byType, OrderPlacedV2.class);

    assertSame(OrderPlaced.class, byType.get(new Key("com.example.OrderPlaced", 1)));
    assertSame(OrderPlacedV2.class, byType.get(new Key("com.example.OrderPlaced", 2)));
  }

  @Test
  void sameClassTwiceIsIdempotent() {
    Map<Key, Class<? extends IntegrationEvent>> byType = new HashMap<>();
    AipersimmonDddOutboxAutoConfiguration.register(byType, OrderPlaced.class);
    AipersimmonDddOutboxAutoConfiguration.register(byType, OrderPlaced.class);

    assertEquals(1, byType.size());
  }

  @Test
  void unannotatedEventFails() {
    Map<Key, Class<? extends IntegrationEvent>> byType = new HashMap<>();

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> AipersimmonDddOutboxAutoConfiguration.register(byType, Unannotated.class));
    assertTrue(thrown.getMessage().contains("@EventType"));
  }

  @Test
  void twoClassesClaimingOneTypeAndVersionFailFast() {
    Map<Key, Class<? extends IntegrationEvent>> byType = new HashMap<>();
    AipersimmonDddOutboxAutoConfiguration.register(byType, OrderPlaced.class);

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> AipersimmonDddOutboxAutoConfiguration.register(byType, OrderPlacedClone.class));
    assertTrue(thrown.getMessage().contains("com.example.OrderPlaced"));
  }
}

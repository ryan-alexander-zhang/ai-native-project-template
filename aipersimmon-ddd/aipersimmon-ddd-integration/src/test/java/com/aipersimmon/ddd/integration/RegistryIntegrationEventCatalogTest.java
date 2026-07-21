package com.aipersimmon.ddd.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.integration.RegistryIntegrationEventCatalog.Key;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RegistryIntegrationEventCatalogTest {

  @EventType(name = "com.example.ordering.OrderPlaced", version = 1)
  record OrderPlaced(String id) implements IntegrationEvent {}

  private static RegistryIntegrationEventCatalog catalogWithOrderPlacedV1() {
    return new RegistryIntegrationEventCatalog(
        Map.of(new Key("com.example.ordering.OrderPlaced", 1), OrderPlaced.class));
  }

  @Test
  void lookupReturnsTheClassForanExactTypeAndVersion() {
    Optional<Class<? extends IntegrationEvent>> found =
        catalogWithOrderPlacedV1().lookup("com.example.ordering.OrderPlaced", 1);

    assertTrue(found.isPresent());
    assertEquals(OrderPlaced.class, found.get());
  }

  @Test
  void lookupMissesOnUnknownTypeOrVersion_withNoFallback() {
    RegistryIntegrationEventCatalog catalog = catalogWithOrderPlacedV1();

    assertTrue(catalog.lookup("com.example.ordering.OrderPlaced", 2).isEmpty(), "wrong version");
    assertTrue(catalog.lookup("com.example.ordering.Other", 1).isEmpty(), "wrong type");
  }

  @Test
  void keyIsComparedByTypeAndVersion() {
    assertEquals(new Key("t", 1), new Key("t", 1));
    assertNotEquals(new Key("t", 1), new Key("t", 2));
    assertNotEquals(new Key("t", 1), new Key("u", 1));
  }

  @Test
  void copiesTheInputMapSoLaterMutationDoesNotLeakIn() {
    Map<Key, Class<? extends IntegrationEvent>> source = new HashMap<>();
    source.put(new Key("com.example.ordering.OrderPlaced", 1), OrderPlaced.class);

    RegistryIntegrationEventCatalog catalog = new RegistryIntegrationEventCatalog(source);
    source.clear();

    assertTrue(catalog.lookup("com.example.ordering.OrderPlaced", 1).isPresent());
  }
}

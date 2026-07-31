package com.aipersimmon.ddd.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The logical type and version are read statically from the required {@link EventType} annotation
 * via {@link IntegrationEvent#eventTypeOf} / {@link IntegrationEvent#eventVersionOf} — the single
 * source of truth (there is no overridable instance method). There is no simple-class-name fallback
 * and no default version: an unannotated, blank-name, or non-positive-version event is a hard
 * error.
 */
class IntegrationEventTest {

  record Unannotated(String id) implements IntegrationEvent {}

  @EventType(name = "", version = 1)
  record BlankName(String id) implements IntegrationEvent {}

  @EventType(name = "com.example.ordering.OrderPlaced", version = 0)
  record BadVersion(String id) implements IntegrationEvent {}

  @EventType(name = "com.example.ordering.OrderPlaced", version = 2)
  record AnnotatedEvent(String id) implements IntegrationEvent {}

  @EventType(name = "com.example.ordering.OrderShipped", version = 1)
  @Externalized("ordering.events")
  record ExternalizedEvent(String id) implements IntegrationEvent {}

  @EventType(name = "com.example.inventory.StockReserved", version = 1, source = "/inventory")
  record SourcedEvent(String id) implements IntegrationEvent {}

  @EventType(name = "com.example.ordering.OrderCancelled", version = 1)
  @Externalized("  ")
  record BlankTargetEvent(String id) implements IntegrationEvent {}

  @Test
  void readsNameAndVersionFromAnnotation() {
    assertEquals(
        "com.example.ordering.OrderPlaced", IntegrationEvent.eventTypeOf(AnnotatedEvent.class));
    assertEquals(2, IntegrationEvent.eventVersionOf(AnnotatedEvent.class));
  }

  /**
   * {@code source} identifies the producing context, which the contract may declare for itself — a
   * deployment hosting several contexts has no single true answer. Undeclared means "use the
   * deployment default", decided by the envelope-minting assembly, not here.
   */
  @Test
  void sourceIsReadFromTheContractWhenDeclared() {
    assertEquals(
        java.util.Optional.of("/inventory"), IntegrationEvent.sourceOf(SourcedEvent.class));
    assertEquals(java.util.Optional.empty(), IntegrationEvent.sourceOf(AnnotatedEvent.class));
  }

  @Test
  void failsWhenNotAnnotated() {
    IllegalStateException type =
        assertThrows(
            IllegalStateException.class, () -> IntegrationEvent.eventTypeOf(Unannotated.class));
    assertTrue(type.getMessage().contains("@EventType"));
    assertThrows(
        IllegalStateException.class, () -> IntegrationEvent.eventVersionOf(Unannotated.class));
  }

  @Test
  void failsWhenNameIsBlank() {
    assertThrows(IllegalStateException.class, () -> IntegrationEvent.eventTypeOf(BlankName.class));
  }

  @Test
  void failsWhenVersionIsNotPositive() {
    assertThrows(
        IllegalStateException.class, () -> IntegrationEvent.eventVersionOf(BadVersion.class));
  }

  @Test
  void externalizedTargetIsTheRawTargetWhenAnnotated() {
    assertEquals(
        Optional.of("ordering.events"),
        IntegrationEvent.externalizedTarget(ExternalizedEvent.class));
  }

  @Test
  void externalizedTargetIsEmptyWhenNotAnnotated() {
    assertTrue(IntegrationEvent.externalizedTarget(AnnotatedEvent.class).isEmpty());
  }

  @Test
  void externalizedTargetFailsWhenTargetIsBlank() {
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () -> IntegrationEvent.externalizedTarget(BlankTargetEvent.class));
    assertTrue(ex.getMessage().contains("blank target"));
  }

  @Test
  void subjectDefaultsToNull() {
    assertNull(new AnnotatedEvent("O-1").subject());
  }
}

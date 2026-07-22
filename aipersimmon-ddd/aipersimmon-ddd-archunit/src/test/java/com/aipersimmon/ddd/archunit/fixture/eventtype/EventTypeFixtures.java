package com.aipersimmon.ddd.archunit.fixture.eventtype;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.IntegrationEvent;

/**
 * Fixtures for {@code EventRules.integrationEventsShouldDeclareEventType}: one integration event
 * per violation kind — missing {@code @EventType}, blank name, version {@code < 1}, and a {@code
 * (name, version)} collision — plus a valid control. Used to characterize every branch of the
 * rule's condition (both its {@code init} collision index and its per-event {@code check}).
 */
public final class EventTypeFixtures {

  private EventTypeFixtures() {}

  /** Missing {@code @EventType} entirely. */
  public record UnannotatedEvent(String id) implements IntegrationEvent {}

  /** {@code @EventType} present but with a blank name. */
  @EventType(name = "", version = 1)
  public record BlankNameEvent(String id) implements IntegrationEvent {}

  /** {@code @EventType} with a version below 1. */
  @EventType(name = "eventtype.zeroVersion", version = 0)
  public record ZeroVersionEvent(String id) implements IntegrationEvent {}

  /** Shares {@code (name, version)} with {@link DuplicateBEvent}. */
  @EventType(name = "eventtype.duplicate", version = 1)
  public record DuplicateAEvent(String id) implements IntegrationEvent {}

  /** Shares {@code (name, version)} with {@link DuplicateAEvent}. */
  @EventType(name = "eventtype.duplicate", version = 1)
  public record DuplicateBEvent(String id) implements IntegrationEvent {}

  /** A valid, unique declaration (control: must not be flagged). */
  @EventType(name = "eventtype.unique", version = 1)
  public record UniqueEvent(String id) implements IntegrationEvent {}
}

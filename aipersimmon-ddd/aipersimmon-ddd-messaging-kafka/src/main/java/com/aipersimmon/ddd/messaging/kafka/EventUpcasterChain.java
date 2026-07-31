package com.aipersimmon.ddd.messaging.kafka;

import com.aipersimmon.ddd.integration.EventUpcaster;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.ResolvableType;

/**
 * The consumer bridge's index of {@link EventUpcaster}s, keyed by the retired revision's class.
 * Applying it walks a deserialized payload hop by hop to the newest revision an upcaster leads to,
 * so listeners face one type per logical event instead of one method per historical version.
 *
 * <p>Every registration is verified at construction, from the two type parameters' own
 * {@code @EventType} contracts: both must resolve to concrete annotated classes, carry the same
 * logical name (an upcaster is a revision bump, not a translation between events), and strictly
 * increase the version — which is also what makes every chain finite: each hop climbs, so there is
 * nothing to cycle through. Two upcasters claiming the same source revision fail startup by name.
 */
final class EventUpcasterChain {

  private static final EventUpcasterChain NONE = new EventUpcasterChain(Map.of());

  private final Map<Class<?>, EventUpcaster<IntegrationEvent, IntegrationEvent>> bySource;

  private EventUpcasterChain(
      Map<Class<?>, EventUpcaster<IntegrationEvent, IntegrationEvent>> bySource) {
    this.bySource = bySource;
  }

  /** No upcasters: every payload is already its newest revision. */
  static EventUpcasterChain none() {
    return NONE;
  }

  @SuppressWarnings("unchecked")
  static EventUpcasterChain of(List<? extends EventUpcaster<?, ?>> upcasters) {
    Map<Class<?>, EventUpcaster<IntegrationEvent, IntegrationEvent>> bySource = new HashMap<>();
    for (EventUpcaster<?, ?> upcaster : upcasters) {
      Class<?> source = typeParameter(upcaster, 0);
      Class<?> target = typeParameter(upcaster, 1);
      String sourceName = IntegrationEvent.eventTypeOf(source);
      String targetName = IntegrationEvent.eventTypeOf(target);
      if (!sourceName.equals(targetName)) {
        throw new IllegalStateException(
            "Upcaster "
                + upcaster.getClass().getName()
                + " maps across logical events ('"
                + sourceName
                + "' -> '"
                + targetName
                + "'); an upcaster is a revision bump of ONE event, so both type parameters must"
                + " declare the same @EventType name");
      }
      int sourceVersion = IntegrationEvent.eventVersionOf(source);
      int targetVersion = IntegrationEvent.eventVersionOf(target);
      if (targetVersion <= sourceVersion) {
        throw new IllegalStateException(
            "Upcaster "
                + upcaster.getClass().getName()
                + " does not increase the version of '"
                + sourceName
                + "' (v"
                + sourceVersion
                + " -> v"
                + targetVersion
                + "); upcasting only moves forward — reading an old revision is what the retired"
                + " class itself is for");
      }
      EventUpcaster<IntegrationEvent, IntegrationEvent> existing =
          bySource.put(source, (EventUpcaster<IntegrationEvent, IntegrationEvent>) upcaster);
      if (existing != null) {
        throw new IllegalStateException(
            "Two upcasters registered for "
                + source.getName()
                + " ('"
                + sourceName
                + "' v"
                + sourceVersion
                + "): "
                + existing.getClass().getName()
                + " and "
                + upcaster.getClass().getName());
      }
    }
    return bySource.isEmpty() ? NONE : new EventUpcasterChain(Map.copyOf(bySource));
  }

  /** Walks {@code payload} to the newest revision an upcaster leads to (itself, if none does). */
  IntegrationEvent upcast(IntegrationEvent payload) {
    IntegrationEvent current = payload;
    EventUpcaster<IntegrationEvent, IntegrationEvent> hop;
    while ((hop = bySource.get(current.getClass())) != null) {
      current = hop.upcast(current);
    }
    return current;
  }

  /**
   * The version a payload of {@code type} will carry after {@link #upcast} — what the skip check
   * must ask the local-handler set about, since that is the class a listener will actually see.
   */
  int terminalVersionOf(Class<?> type) {
    Class<?> current = type;
    EventUpcaster<IntegrationEvent, IntegrationEvent> hop;
    while ((hop = bySource.get(current)) != null) {
      current = typeParameter(hop, 1);
    }
    return IntegrationEvent.eventVersionOf(current);
  }

  /** Whether any upcaster is registered at all (lets the skip check stay cheap when none is). */
  boolean isEmpty() {
    return bySource.isEmpty();
  }

  private static Class<?> typeParameter(EventUpcaster<?, ?> upcaster, int index) {
    Class<?> type =
        ResolvableType.forInstance(upcaster).as(EventUpcaster.class).getGeneric(index).resolve();
    // Stricter than null: an erased parameter resolves to its bound (the IntegrationEvent
    // interface), and an upcaster indexed under the interface would silently never apply.
    if (type == null || type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
      throw new IllegalStateException(
          "Cannot resolve type parameter "
              + index
              + " of upcaster "
              + upcaster.getClass().getName()
              + " (got "
              + (type == null ? "nothing" : type.getName())
              + "); declare it with the two concrete event revision classes");
    }
    return type;
  }
}

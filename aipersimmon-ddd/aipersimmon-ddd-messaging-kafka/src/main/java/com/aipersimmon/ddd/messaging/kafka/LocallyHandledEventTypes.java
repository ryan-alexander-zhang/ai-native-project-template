package com.aipersimmon.ddd.messaging.kafka;

import com.aipersimmon.ddd.integration.EventEnvelope;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import com.aipersimmon.ddd.integration.RegistryIntegrationEventCatalog.Key;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;

/**
 * The set of integration-event {@code (type, version)}s the application has a <em>local</em>
 * {@code @EventListener(EventEnvelope<T>)} for. The consumer bridge uses it to short-circuit: a
 * consumed record whose type nobody handles is dropped <strong>before</strong> the inbox write /
 * reconstruct / republish, because with no handler there is no side effect to make atomic with the
 * inbox — so there is nothing to record and nothing to redeliver.
 *
 * <p><strong>Safety is asymmetric and the scan is deliberately conservative.</strong> Skipping an
 * event that <em>is</em> handled would silently lose it, so the rule is "skip only when we are
 * certain nothing handles it": {@link #handlesAll()} is {@code true} (⇒ never skip) whenever a
 * listener could match more than one concrete type — a raw or wildcard {@code EventEnvelope}, a
 * supertype like {@code Object}, a {@code classes()}-declared listener we cannot read the generic
 * of, or a payload type we cannot resolve to a well-formed {@link
 * com.aipersimmon.ddd.integration.EventType @EventType}. Only unambiguous
 * {@code @EventListener(EventEnvelope<ConcreteEvent>)} handlers narrow the set. The escape hatch
 * for applications that consume via a mechanism this scan cannot see (a programmatic {@code
 * ApplicationListener}) is the {@code consumer.skip-locally-unhandled=false} property, which
 * supplies {@link #handlingEverything()}.
 */
final class LocallyHandledEventTypes {

  private final Set<Key> handled;
  private final boolean handlesAll;

  private LocallyHandledEventTypes(Set<Key> handled, boolean handlesAll) {
    this.handled = handled;
    this.handlesAll = handlesAll;
  }

  /** Handles every type — nothing is ever skipped (the safe default / opt-out). */
  static LocallyHandledEventTypes handlingEverything() {
    return new LocallyHandledEventTypes(Set.of(), true);
  }

  /** Handles exactly the given keys (used by tests and callers that already know the set). */
  static LocallyHandledEventTypes handling(Set<Key> keys) {
    return new LocallyHandledEventTypes(Set.copyOf(keys), false);
  }

  /**
   * {@code true} if a record of this {@code (type, version)} has a local handler (or if unsure).
   */
  boolean handles(String type, int version) {
    return handlesAll || handled.contains(new Key(type, version));
  }

  /** {@code true} if the set could not be narrowed, so nothing may be skipped. */
  boolean handlesAll() {
    return handlesAll;
  }

  /**
   * Scans every bean's methods for {@code @EventListener} (including meta-annotated ones such as
   * {@code @TransactionalEventListener}) and narrows the handled set from the ones that
   * unambiguously take a concrete {@code EventEnvelope<ConcreteEvent>}; anything ambiguous makes
   * the result {@link #handlesAll()}. Reads bean <em>types</em> (not instances), so it does not
   * force eager initialization.
   */
  static LocallyHandledEventTypes scan(ConfigurableListableBeanFactory beanFactory) {
    Set<Key> handled = new HashSet<>();
    for (String beanName : beanFactory.getBeanDefinitionNames()) {
      Class<?> beanType;
      try {
        beanType = beanFactory.getType(beanName);
      } catch (RuntimeException ex) {
        // A bean whose type cannot be resolved without instantiating it: skip it for the
        // scan and stay safe by treating the whole result as "handles all".
        return handlingEverything();
      }
      if (beanType == null) {
        continue;
      }
      var methods =
          MethodIntrospector.selectMethods(
              ClassUtils.getUserClass(beanType),
              (MethodIntrospector.MetadataLookup<EventListener>)
                  m -> AnnotatedElementUtils.findMergedAnnotation(m, EventListener.class));
      for (var entry : methods.entrySet()) {
        if (!narrow(entry.getKey(), entry.getValue(), handled)) {
          // could not narrow this listener to a single concrete event type -> be safe
          return handlingEverything();
        }
      }
    }
    return new LocallyHandledEventTypes(Set.copyOf(handled), false);
  }

  /**
   * Adds the concrete type this listener handles to {@code handled}, or returns {@code false} if
   * the listener could match more than one type (so the caller must fall back to {@link
   * #handlingEverything()}). A listener whose parameter cannot receive an {@code EventEnvelope} at
   * all is unrelated and returns {@code true} without adding anything.
   */
  private static boolean narrow(Method method, EventListener annotation, Set<Key> handled) {
    // Event type declared via classes(): we cannot read a generic payload from it, so if any
    // declared class could be an EventEnvelope we must not narrow.
    if (annotation.classes().length > 0) {
      for (Class<?> declared : annotation.classes()) {
        if (couldReceiveEnvelope(declared)) {
          return false;
        }
      }
      return true; // classes() are all unrelated event types
    }
    if (method.getParameterCount() != 1) {
      return true; // not a single-arg listener; Spring resolves its type elsewhere, ignore
    }
    ResolvableType param = ResolvableType.forMethodParameter(method, 0);
    Class<?> paramClass = param.toClass();
    if (!couldReceiveEnvelope(paramClass)) {
      return true; // a listener for some unrelated event
    }
    if (paramClass == EventEnvelope.class) {
      Class<?> payload = param.getGeneric(0).resolve();
      if (payload != null
          && payload != IntegrationEvent.class
          && IntegrationEvent.class.isAssignableFrom(payload)) {
        try {
          handled.add(
              new Key(
                  IntegrationEvent.eventTypeOf(payload), IntegrationEvent.eventVersionOf(payload)));
          return true;
        } catch (RuntimeException ex) {
          // payload isn't a well-formed @EventType (can't happen for a real event, but
          // don't let the scan throw or wrongly narrow) -> be safe
        }
      }
    }
    return false; // raw/wildcard EventEnvelope, a supertype, or an unresolvable payload
  }

  /**
   * Whether an {@code EventEnvelope} could be delivered to a listener declared for {@code type}.
   */
  private static boolean couldReceiveEnvelope(Class<?> type) {
    return type.isAssignableFrom(EventEnvelope.class) || EventEnvelope.class.isAssignableFrom(type);
  }
}

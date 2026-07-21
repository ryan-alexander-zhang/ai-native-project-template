package com.aipersimmon.ddd.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

import com.aipersimmon.ddd.application.DomainEventHandler;
import com.aipersimmon.ddd.core.event.DomainEvent;
import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Event rules: where domain events and integration events live, where their subscribers live, and
 * that each integration event declares a valid, unique published type.
 *
 * <p>All but {@link #integrationEventsShouldResideInApi()} are bundled into {@link
 * AiPersimmonDddRules#all()}; that one is opt-in because it presumes the {@code ..api..}
 * published-contract convention.
 */
public final class EventRules {

  /**
   * Spring's event-subscriber annotation, matched by fully-qualified name so the event-listener
   * placement rules stay free of a compile dependency on Spring: a project that does not use Spring
   * simply has no matching methods, and the rule passes vacuously.
   */
  private static final String SPRING_EVENT_LISTENER =
      "org.springframework.context.event.EventListener";

  private EventRules() {}

  /**
   * Domain events belong to the domain layer, not to the interface or integration layers. Matches a
   * type declared as a domain event <em>either</em> way the core offers — implementing the {@link
   * DomainEvent} marker interface or carrying the {@link
   * com.aipersimmon.ddd.core.annotation.DomainEvent @DomainEvent} annotation — since both express
   * the same role and the annotation path must be guarded too.
   */
  public static ArchRule domainEventsShouldStayInDomain() {
    return classes()
        .that()
        .implement(DomainEvent.class)
        .or()
        .areAnnotatedWith(com.aipersimmon.ddd.core.annotation.DomainEvent.class)
        .should()
        .resideInAPackage("..domain..")
        .as("domain events should reside in the domain layer")
        .because(
            "a domain event is an internal fact of the bounded context, "
                + "not a cross-context contract or a delivery concern")
        .allowEmptyShould(true);
  }

  /**
   * A subscriber of an in-process domain event (an {@code @EventListener} method whose argument is
   * a {@link DomainEvent}) resides in the application layer (or the domain), never in an inbound
   * adapter. A domain event is consumed within its own bounded context; its subscriber orchestrates
   * a use case or starts a process, which is application (or domain) work — not the transport
   * translation an adapter does. Part of {@link AiPersimmonDddRules#all()}; the rule matches
   * nothing (and so passes) in a project that has no such subscribers.
   */
  public static ArchRule domainEventListenersShouldResideInApplicationOrDomain() {
    return methods()
        .that(areEventListenersHandling(DomainEvent.class))
        .should()
        .beDeclaredInClassesThat()
        .resideInAnyPackage("..application..", "..domain..")
        .as("domain-event @EventListener handlers should reside in the application or domain layer")
        .because(
            "a domain event is consumed within its bounded context; its subscriber belongs to "
                + "the application (or domain) layer, not to an inbound adapter")
        .allowEmptyShould(true);
  }

  /**
   * A subscriber of an integration event (an {@code @EventListener} method whose argument is an
   * {@link IntegrationEvent}) resides in the interface/adapter layer. An integration event arrives
   * from another context over a transport; the subscriber is the inbound adapter that receives it
   * at the boundary and translates it into a command (or hands a correlation id to a process
   * manager) — it holds no orchestration or domain logic itself. Part of {@link
   * AiPersimmonDddRules#all()}; the rule matches nothing (and so passes) in a project that has no
   * such subscribers.
   */
  public static ArchRule integrationEventListenersShouldResideInAdapter() {
    return methods()
        .that(areEventListenersHandling(IntegrationEvent.class))
        .should()
        .beDeclaredInClassesThat()
        .resideInAPackage("..adapter..")
        .as(
            "integration-event @EventListener handlers should reside in the interface/adapter layer")
        .because(
            "an integration event arrives over a transport at the boundary, so its subscriber is "
                + "an inbound adapter that translates it and hands off inward")
        .allowEmptyShould(true);
  }

  /**
   * A subscriber of an in-process domain event (an {@code @EventListener} method whose argument is
   * a {@link DomainEvent}) is declared in a class annotated {@link
   * DomainEventHandler @DomainEventHandler}. This makes the subscriber's role explicit and lets
   * tools locate domain-event handlers by annotation rather than by a naming or parameter-shape
   * heuristic. Pairs with {@link #domainEventListenersShouldResideInApplicationOrDomain()}: that
   * fixes the layer, this requires the marker. Part of {@link AiPersimmonDddRules#all()}; matches
   * nothing (and so passes) in a project that has no such subscribers.
   */
  public static ArchRule domainEventListenersShouldBeAnnotatedWithDomainEventHandler() {
    return methods()
        .that(areEventListenersHandling(DomainEvent.class))
        .should()
        .beDeclaredInClassesThat()
        .areAnnotatedWith(DomainEventHandler.class)
        .as(
            "domain-event @EventListener handlers should be declared in a @DomainEventHandler class")
        .because(
            "a domain-event subscriber is a first-class application concern, marked as such so "
                + "its role is explicit and architecture tests can find it by annotation")
        .allowEmptyShould(true);
  }

  /**
   * An {@link IntegrationEvent} declares a valid, unique logical type with the {@link
   * EventType @EventType} annotation: it is present, its {@code name} is non-blank, its {@code
   * version} is {@code >= 1}, and no two events share a {@code name}. The logical type is the
   * event's published identity on the wire — it must be an explicit, stable, versioned contract,
   * never derived from the Java class name (an implementation detail that changes on a rename and
   * collides across contexts). Mirrors the runtime checks in {@link IntegrationEvent#eventTypeOf} /
   * {@link IntegrationEvent#eventVersionOf} and the type registry, catching them at build time —
   * before anything is published or a consumer's registry is built. Part of {@link
   * AiPersimmonDddRules#all()}; matches nothing (and so passes) in a project that declares no
   * integration events.
   */
  public static ArchRule integrationEventsShouldDeclareEventType() {
    return classes()
        .that()
        .implement(IntegrationEvent.class)
        .should(declareAValidUniqueEventType())
        .as("integration events should declare a valid, unique @EventType (name + version)")
        .because(
            "the logical event type is a published contract that must be declared explicitly "
                + "and unambiguously, not derived from the Java class name")
        .allowEmptyShould(true);
  }

  /**
   * An {@link IntegrationEvent} — a fact one bounded context publishes for others — resides in the
   * context's {@code ..api..} package, its published contract. It is the mirror of {@link
   * #domainEventsShouldStayInDomain()}: a domain event stays private to the domain, while an
   * integration event is deliberately exposed, so it lives with the rest of the outward contract
   * rather than in the domain, application, or adapter internals. Deliberately <strong>not</strong>
   * part of {@link AiPersimmonDddRules#all()}: it presumes the {@code ..api..} published-contract
   * convention (used by the modulith and multi-module layouts); a layout that publishes contracts
   * elsewhere — for example a shared {@code contracts} module in a microservice split — would adopt
   * its own variant. Adopt it alongside {@link AiPersimmonDddRules#all()}:
   *
   * <pre>{@code
   * @ArchTest static final ArchRule events = EventRules.integrationEventsShouldResideInApi();
   * }</pre>
   */
  public static ArchRule integrationEventsShouldResideInApi() {
    return classes()
        .that()
        .implement(IntegrationEvent.class)
        .should()
        .resideInAPackage("..api..")
        .as("integration events should reside in the ..api.. published-contract package")
        .because(
            "an integration event is a fact published for other contexts, so it belongs with "
                + "the outward contract, not in the domain, application, or adapter internals")
        .allowEmptyShould(true);
  }

  /**
   * Reports a violation for an {@link IntegrationEvent} that is missing {@link EventType}, declares
   * a blank {@code name} or a {@code version < 1}, or shares its {@code (name, version)} with
   * another event. Two events sharing a name but with different versions are allowed — that is how
   * a type's revisions coexist. The {@code (name, version)} collisions are computed once in {@code
   * init} over all the events being checked, so both classes in a clash are reported.
   */
  private static ArchCondition<JavaClass> declareAValidUniqueEventType() {
    Map<String, List<String>> classesByTypeAndVersion = new HashMap<>();
    return new ArchCondition<>("declare a valid, unique @EventType (name + version)") {
      @Override
      public void init(Collection<JavaClass> events) {
        classesByTypeAndVersion.clear();
        for (JavaClass event : events) {
          if (event.isAnnotatedWith(EventType.class)) {
            EventType annotation = event.getAnnotationOfType(EventType.class);
            if (!annotation.name().isBlank()) {
              classesByTypeAndVersion
                  .computeIfAbsent(key(annotation), key -> new ArrayList<>())
                  .add(event.getFullName());
            }
          }
        }
      }

      @Override
      public void check(JavaClass event, ConditionEvents events) {
        if (!event.isAnnotatedWith(EventType.class)) {
          events.add(
              SimpleConditionEvent.violated(
                  event,
                  event.getFullName()
                      + " is an IntegrationEvent but is not annotated with @EventType"));
          return;
        }
        EventType annotation = event.getAnnotationOfType(EventType.class);
        if (annotation.name().isBlank()) {
          events.add(
              SimpleConditionEvent.violated(
                  event, event.getFullName() + " declares a blank @EventType name"));
        }
        if (annotation.version() < 1) {
          events.add(
              SimpleConditionEvent.violated(
                  event,
                  event.getFullName()
                      + " declares @EventType version "
                      + annotation.version()
                      + ", which must be >= 1"));
        }
        List<String> sharing = classesByTypeAndVersion.get(key(annotation));
        if (sharing != null && sharing.size() > 1) {
          events.add(
              SimpleConditionEvent.violated(
                  event,
                  event.getFullName()
                      + " shares @EventType (name '"
                      + annotation.name()
                      + "', version "
                      + annotation.version()
                      + ") with "
                      + sharing));
        }
      }

      private String key(EventType annotation) {
        return annotation.name() + ' ' + annotation.version();
      }
    };
  }

  /**
   * A method that both carries Spring's {@code @EventListener} (matched by name, see {@link
   * #SPRING_EVENT_LISTENER}) and takes a parameter assignable to the given event marker — i.e. an
   * event subscriber for that kind of event.
   */
  private static DescribedPredicate<JavaMethod> areEventListenersHandling(Class<?> eventMarker) {
    return DescribedPredicate.describe(
        "@EventListener methods handling a " + eventMarker.getSimpleName(),
        method ->
            method.isAnnotatedWith(SPRING_EVENT_LISTENER)
                && method.getRawParameterTypes().stream()
                    .anyMatch(parameter -> parameter.isAssignableTo(eventMarker)));
  }
}

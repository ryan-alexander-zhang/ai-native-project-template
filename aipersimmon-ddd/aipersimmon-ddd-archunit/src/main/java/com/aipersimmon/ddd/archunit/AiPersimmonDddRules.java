package com.aipersimmon.ddd.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.aipersimmon.ddd.application.DomainEventHandler;
import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.annotation.Entity;
import com.aipersimmon.ddd.core.annotation.Repository;
import com.aipersimmon.ddd.core.annotation.Service;
import com.aipersimmon.ddd.core.annotation.ValueObject;
import com.aipersimmon.ddd.core.error.ErrorCode;
import com.aipersimmon.ddd.core.event.DomainEvent;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;
import com.aipersimmon.ddd.core.rule.Invariant;
import com.aipersimmon.ddd.core.rule.InvariantViolationException;
import com.aipersimmon.ddd.core.state.IllegalStateTransitionException;
import com.aipersimmon.ddd.core.state.Transitions;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reusable ArchUnit rules enforcing the DDD layering and building-block conventions. They match
 * layers by the package segment they live in ({@code ..domain..}, {@code ..application..}, {@code
 * ..infrastructure..}, {@code ..adapter..}), so they hold whether a layer is a sub-package (single
 * deployable) or its own module (multi-module build) — the segment is present either way.
 *
 * <p>Wire them into a test and let ArchUnit scope which classes are analysed:
 *
 * <pre>{@code
 * @AnalyzeClasses(packages = "com.example.app")
 * class ArchitectureTest {
 *     @ArchTest static final ArchRule ddd = AiPersimmonDddRules.all();
 * }
 * }</pre>
 *
 * <p>Every rule tolerates an empty match, so a project that has not yet introduced a given layer
 * still passes rather than erroring.
 *
 * <h2>Rules bundled by {@link #all()}</h2>
 *
 * These are framework-agnostic — each passes vacuously without the framework it concerns — so
 * {@code all()} is safe for any layout, single-deployable or multi-module:
 *
 * <ul>
 *   <li><em>Layering</em> — {@link #domainShouldNotDependOnOuterLayers()}, {@link
 *       #applicationShouldNotDependOnInfrastructureOrInterface()}, {@link
 *       #domainShouldBeFrameworkFree()}.
 *   <li><em>Events</em> — {@link #domainEventsShouldStayInDomain()}, {@link
 *       #domainEventListenersShouldResideInApplicationOrDomain()}, {@link
 *       #integrationEventListenersShouldResideInAdapter()}, {@link
 *       #domainEventListenersShouldBeAnnotatedWithDomainEventHandler()}, {@link
 *       #integrationEventsShouldDeclareEventType()}.
 *   <li><em>CQRS</em> — {@link #commandHandlersShouldNotDependOnOtherCommandHandlers()}, {@link
 *       #commandHandlersAndApplicationShouldNotCallSendAs()}.
 *   <li><em>Building blocks</em> — {@link #domainBuildingBlocksShouldResideInDomain()}, {@link
 *       #domainServicesShouldResideInDomain()}, {@link
 *       #aggregateRootsShouldExtendAbstractAggregateRoot()}, {@link
 *       #valueObjectsShouldBeImmutable()}.
 *   <li><em>Repositories</em> — {@link #repositoryPortsShouldBeInterfacesInDomain()}, {@link
 *       #repositoryImplementationsShouldResideInInfrastructure()}.
 *   <li><em>Invariants, state &amp; errors</em> — {@link #invariantsShouldResideInDomain()}, {@link
 *       #invariantViolationsShouldOnlyComeFromCheckInvariant()}, {@link
 *       #invariantsShouldNotBeSpringComponents()}, {@link
 *       #illegalStateTransitionsShouldOnlyComeFromTransitions()}, {@link
 *       #errorCodesShouldBeEnums()}.
 * </ul>
 *
 * <h2>Opt-in rules (not in {@code all()})</h2>
 *
 * Each is left out of the bundle because it presumes something {@code all()} must not — a stricter
 * layout, a specific framework, a packaging convention, or a parameter — so a project adopts it
 * deliberately, alongside {@code all()}:
 *
 * <ul>
 *   <li>{@link #adapterShouldNotDependOnDomain()} — stricter hexagonal discipline; forbids
 *       <em>every</em> adapter&#8594;domain reference.
 *   <li>{@link #repositoryImplementationsShouldBeSpringRepositories()} — presumes Spring.
 *   <li>{@link #integrationEventsShouldResideInApi()} — presumes the {@code ..api..}
 *       published-contract convention.
 *   <li>{@link #boundedContextsShouldOnlyDependOnEachOthersApi(String)} — parameterised on the base
 *       package under which each sub-package is a bounded context.
 * </ul>
 *
 * <p>{@link PackageInfoChecks} is a separate, source-level companion (a {@code package-info.java}
 * without annotations produces no class file, so bytecode analysis cannot see it); wire it into a
 * plain {@code @Test}.
 */
public final class AiPersimmonDddRules {

  /**
   * Packages considered technical frameworks that the domain layer must not touch. This is a
   * sensible default; a project may add its own rule for frameworks specific to it.
   */
  private static final String[] FRAMEWORK_PACKAGES = {
    "org.springframework..",
    "jakarta.persistence..",
    "javax.persistence..",
    "org.hibernate..",
    "org.apache.ibatis..",
    "com.baomidou..",
    "com.fasterxml.jackson..",
  };

  /**
   * Spring's event-subscriber annotation, matched by fully-qualified name so the event-listener
   * placement rules stay free of a compile dependency on Spring: a project that does not use Spring
   * simply has no matching methods, and the rule passes vacuously.
   */
  private static final String SPRING_EVENT_LISTENER =
      "org.springframework.context.event.EventListener";

  /**
   * Spring's {@code @Repository} stereotype, matched by fully-qualified name so the Spring-specific
   * repository rule stays free of a compile dependency on Spring.
   */
  private static final String SPRING_REPOSITORY = "org.springframework.stereotype.Repository";

  private AiPersimmonDddRules() {}

  /** The domain layer must not depend on the layers built on top of it. */
  public static ArchRule domainShouldNotDependOnOuterLayers() {
    return noClasses()
        .that()
        .resideInAPackage("..domain..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("..application..", "..infrastructure..", "..adapter..")
        .as(
            "domain classes should not depend on the application, infrastructure, or interface layers")
        .because("the domain layer must stay independent of the layers built on top of it")
        .allowEmptyShould(true);
  }

  /** The application layer must not depend on infrastructure or the interface layer. */
  public static ArchRule applicationShouldNotDependOnInfrastructureOrInterface() {
    return noClasses()
        .that()
        .resideInAPackage("..application..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("..infrastructure..", "..adapter..")
        .as("application classes should not depend on the infrastructure or interface layers")
        .because("use-case orchestration must depend inward on the domain only")
        .allowEmptyShould(true);
  }

  /** The domain layer must be free of technical frameworks. */
  public static ArchRule domainShouldBeFrameworkFree() {
    return noClasses()
        .that()
        .resideInAPackage("..domain..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(FRAMEWORK_PACKAGES)
        .as("domain classes should not depend on technical frameworks")
        .because("the domain layer must be free of Spring, JPA, and other framework concerns")
        .allowEmptyShould(true);
  }

  /**
   * Stricter, <em>opt-in</em> rule: the interface/adapter layer must not depend on the domain
   * directly, driving use cases through the application layer instead.
   *
   * <p>Deliberately <strong>not</strong> part of {@link #all()}: it forbids <em>every</em>
   * adapter&#8594;domain reference, which some layouts legitimately need — for example a project
   * that keeps its persistence adapters (repository implementations that map aggregates) in the
   * same module as its inbound adapters. Adopt it in projects that separate persistence adapters
   * out and want the tighter hexagonal discipline where every driving adapter goes through the
   * application layer, and add it to a test alongside {@link #all()}:
   *
   * <pre>{@code
   * @ArchTest static final ArchRule adapters = AiPersimmonDddRules.adapterShouldNotDependOnDomain();
   * }</pre>
   */
  public static ArchRule adapterShouldNotDependOnDomain() {
    return noClasses()
        .that()
        .resideInAPackage("..adapter..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("..domain..")
        .as("interface/adapter classes should not depend on the domain layer directly")
        .because(
            "driving adapters should invoke use cases through the application layer, "
                + "rather than reaching into domain internals")
        .allowEmptyShould(true);
  }

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
   * translation an adapter does. Part of {@link #all()}; the rule matches nothing (and so passes)
   * in a project that has no such subscribers.
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
   * manager) — it holds no orchestration or domain logic itself. Part of {@link #all()}; the rule
   * matches nothing (and so passes) in a project that has no such subscribers.
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
   * fixes the layer, this requires the marker. Part of {@link #all()}; matches nothing (and so
   * passes) in a project that has no such subscribers.
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
   * before anything is published or a consumer's registry is built. Part of {@link #all()}; matches
   * nothing (and so passes) in a project that declares no integration events.
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
        return annotation.name() + ' ' + annotation.version();
      }
    };
  }

  /**
   * A {@link CommandHandler} implementation must not depend on another {@link CommandHandler}
   * implementation. A command handler is an entry point on the command bus, not an internal API:
   * one handler invoking another either bypasses the callee's {@code CommandInterceptor} chain (its
   * transaction, validation, logging) or, if routed back through the bus, nests transactions and
   * double-applies those concerns; it also blurs the unit-of-work boundary and couples two use
   * cases that should evolve independently. Reusable logic belongs in a domain service or a
   * non-handler application collaborator, injected into both handlers — see {@code decision-00010}.
   * Part of {@link #all()}; matches nothing (and so passes) in a project that has no command
   * handlers.
   */
  public static ArchRule commandHandlersShouldNotDependOnOtherCommandHandlers() {
    return classes()
        .that()
        .implement(CommandHandler.class)
        .should(notDependOnAnotherCommandHandler())
        .as("command handlers should not depend on other command handlers")
        .because(
            "a CommandHandler is a command-bus entry point, not an internal API; reuse belongs "
                + "in a domain service or a non-handler application collaborator, not in a "
                + "handler-to-handler dependency")
        .allowEmptyShould(true);
  }

  /**
   * Reports a violation for each dependency whose target is a {@link CommandHandler} implementation
   * other than the {@code CommandHandler} interface itself and other than the origin class.
   * Excluding the interface keeps a handler's own {@code implements CommandHandler} from counting;
   * excluding the origin keeps a self-reference from counting. Used with {@code
   * classes().should(...)}, so a {@code violated} event is a rule violation.
   */
  /**
   * Command handlers and application code must not call {@code CommandBus.sendAs(..)}.
   *
   * <p>{@code sendAs} is the durable-runtime / outbox staged-dispatch entry point: it replays a
   * command under a message identity that was already minted and persisted upstream (a Process
   * Manager effect row, an outbox row), using that identity verbatim. It exists so an at-least-once
   * relay can redeliver the same effect under a stable messageId. A handler or application class
   * calling it would fabricate message identity outside the sanctioned minting authorities and
   * bypass the causation chain (see decision-00016-durable-runtime-staged-message-identity, patch
   * of decision-00013). Business dispatch uses {@link CommandBus#send(Command)} / {@code
   * send(Command, CommandContext)}.
   *
   * <p>Passes vacuously until {@code sendAs} and a violating call site exist; framework-agnostic,
   * so it is safe in {@link #all()}.
   */
  public static ArchRule commandHandlersAndApplicationShouldNotCallSendAs() {
    return classes()
        .that()
        .implement(CommandHandler.class)
        .or()
        .resideInAPackage("..application..")
        .should(notCallCommandBusSendAs())
        .as("command handlers and application code should not call CommandBus.sendAs(..)")
        .because(
            "sendAs replays a pre-minted, persisted message identity verbatim and is reserved "
                + "for durable infrastructure (effect relay / outbox dispatcher); business code "
                + "dispatches with send(..) / send(.., cause) and never mints staged identities")
        .allowEmptyShould(true);
  }

  private static ArchCondition<JavaClass> notCallCommandBusSendAs() {
    return new ArchCondition<>("not call CommandBus.sendAs(..)") {
      @Override
      public void check(JavaClass origin, ConditionEvents events) {
        origin
            .getMethodCallsFromSelf()
            .forEach(
                call -> {
                  boolean callsSendAs =
                      call.getTarget().getName().equals("sendAs")
                          && call.getTarget().getOwner().isAssignableTo(CommandBus.class);
                  if (callsSendAs) {
                    events.add(SimpleConditionEvent.violated(call, call.getDescription()));
                  }
                });
      }
    };
  }

  private static ArchCondition<JavaClass> notDependOnAnotherCommandHandler() {
    return new ArchCondition<>("not depend on another CommandHandler implementation") {
      @Override
      public void check(JavaClass origin, ConditionEvents events) {
        origin
            .getDirectDependenciesFromSelf()
            .forEach(
                dependency -> {
                  JavaClass target = dependency.getTargetClass();
                  boolean anotherHandler =
                      target.isAssignableTo(CommandHandler.class)
                          && !target.isEquivalentTo(CommandHandler.class)
                          && !target.getName().equals(origin.getName());
                  if (anotherHandler) {
                    events.add(
                        SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                  }
                });
      }
    };
  }

  /**
   * An {@link Invariant} — a business invariant expressed as an object — resides in the domain
   * layer. It captures domain policy, so it belongs with the model, not in the application,
   * infrastructure, or interface layers. Part of {@link #all()}; matches nothing (and so passes) in
   * a project that defines no invariants.
   */
  public static ArchRule invariantsShouldResideInDomain() {
    return classes()
        .that()
        .implement(Invariant.class)
        .should()
        .resideInAPackage("..domain..")
        .as("Invariant implementations should reside in the domain layer")
        .because("an invariant is a domain rule, so it belongs with the model")
        .allowEmptyShould(true);
  }

  /**
   * An {@link InvariantViolationException} is raised only through {@link
   * AbstractAggregateRoot#checkInvariant(Invariant)} — never constructed directly by application,
   * domain, or adapter code. Routing every violation through {@code checkInvariant} keeps invariant
   * enforcement uniform and the invariant object the single source of the message and code. Part of
   * {@link #all()}; matches nothing (and so passes) in a project that never constructs it directly.
   */
  public static ArchRule invariantViolationsShouldOnlyComeFromCheckInvariant() {
    return noClasses()
        .that()
        .doNotHaveFullyQualifiedName(AbstractAggregateRoot.class.getName())
        .should()
        .callConstructor(InvariantViolationException.class, Invariant.class)
        .as(
            "InvariantViolationException should be raised only via AbstractAggregateRoot.checkInvariant")
        .because(
            "routing every invariant violation through checkInvariant keeps enforcement uniform and "
                + "the invariant object the single source of its message and code")
        .allowEmptyShould(true);
  }

  /**
   * An {@link Invariant} is a plain domain object, not a Spring-managed bean: it must not carry a
   * Spring stereotype ({@code @Component} or a meta-annotation of it such as
   * {@code @Service}/{@code @Repository}). Matched by fully-qualified name so the rule needs no
   * compile dependency on Spring. Part of {@link #all()}; matches nothing (and so passes) in a
   * project whose invariants are not Spring beans.
   */
  public static ArchRule invariantsShouldNotBeSpringComponents() {
    return noClasses()
        .that()
        .implement(Invariant.class)
        .should()
        .beAnnotatedWith("org.springframework.stereotype.Component")
        .orShould()
        .beMetaAnnotatedWith("org.springframework.stereotype.Component")
        .as("Invariant implementations should not be Spring components")
        .because(
            "an invariant is a plain domain object constructed where it is "
                + "checked, not a container-managed bean")
        .allowEmptyShould(true);
  }

  /**
   * The tactical building blocks that make up an aggregate — a type carrying {@link
   * AggregateRoot @AggregateRoot}, {@link Entity @Entity}, or {@link ValueObject @ValueObject} —
   * reside in the domain layer. Each marks a model concept, so it belongs with the model, never in
   * the application, infrastructure, or interface layers. Part of {@link #all()}; matches nothing
   * (and so passes) in a project that annotates no building blocks.
   */
  public static ArchRule domainBuildingBlocksShouldResideInDomain() {
    return classes()
        .that()
        .areAnnotatedWith(AggregateRoot.class)
        .or()
        .areAnnotatedWith(Entity.class)
        .or()
        .areAnnotatedWith(ValueObject.class)
        .should()
        .resideInAPackage("..domain..")
        .as("@AggregateRoot, @Entity, and @ValueObject types should reside in the domain layer")
        .because(
            "aggregate roots, entities, and value objects are model concepts that belong with "
                + "the domain, not in the application, infrastructure, or interface layers")
        .allowEmptyShould(true);
  }

  /**
   * A domain service — a type carrying {@link Service @Service} — resides in the domain layer. It
   * is stateless domain behaviour that does not sit naturally on a single entity or value object,
   * so it belongs with the model rather than in an application, infrastructure, or interface
   * package. Matched by the core {@code @Service} annotation, not Spring's stereotype, so an
   * application component annotated with Spring's {@code @Service} is unaffected. Part of {@link
   * #all()}; matches nothing (and so passes) in a project that declares no domain services.
   */
  public static ArchRule domainServicesShouldResideInDomain() {
    return classes()
        .that()
        .areAnnotatedWith(Service.class)
        .should()
        .resideInAPackage("..domain..")
        .as("@Service (domain service) types should reside in the domain layer")
        .because("a domain service is stateless domain behaviour, so it belongs with the model")
        .allowEmptyShould(true);
  }

  /**
   * A type marked {@link AggregateRoot @AggregateRoot} extends {@link AbstractAggregateRoot}, so it
   * actually carries the aggregate lifecycle — recording domain events and enforcing invariants
   * through {@code checkInvariant} — rather than only claiming the role by annotation. Pairs with
   * {@link #domainBuildingBlocksShouldResideInDomain()}: that fixes the layer, this requires the
   * base class. Part of {@link #all()}; matches nothing (and so passes) in a project with no
   * annotated aggregate roots.
   */
  public static ArchRule aggregateRootsShouldExtendAbstractAggregateRoot() {
    return classes()
        .that()
        .areAnnotatedWith(AggregateRoot.class)
        .should()
        .beAssignableTo(AbstractAggregateRoot.class)
        .as("@AggregateRoot types should extend AbstractAggregateRoot")
        .because(
            "an aggregate root records domain events and enforces invariants through the base "
                + "class, so the annotation and the lifecycle it implies must not drift apart")
        .allowEmptyShould(true);
  }

  /**
   * A value object — a type carrying {@link ValueObject @ValueObject} — has only final fields, so
   * it cannot be mutated after construction. A value object is defined by its attributes and
   * compared by their equality; letting a field change would give it identity-like behaviour and
   * break that contract. A {@code record} satisfies this for free; a class must declare its fields
   * {@code final}. Part of {@link #all()}; matches nothing (and so passes) in a project that
   * annotates no value objects.
   */
  public static ArchRule valueObjectsShouldBeImmutable() {
    return classes()
        .that()
        .areAnnotatedWith(ValueObject.class)
        .should()
        .haveOnlyFinalFields()
        .as("@ValueObject types should be immutable (have only final fields)")
        .because(
            "a value object is defined by its attributes and compared by their equality, so it "
                + "must not change after construction")
        .allowEmptyShould(true);
  }

  /**
   * An {@link IllegalStateTransitionException} is raised only from within {@link
   * Transitions#check(Object, Object)} — never constructed directly by domain, application, or
   * adapter code. Declaring the legal transitions in a {@link Transitions} table and routing every
   * check through it keeps the transition rules in one place and the exception's message uniform,
   * exactly as {@link #invariantViolationsShouldOnlyComeFromCheckInvariant()} does for invariants.
   * Guards the {@code (from, to)} constructor that {@code Transitions} uses; the {@link
   * ErrorCode}-carrying overload is a deliberate escape hatch and is not covered. Part of {@link
   * #all()}; matches nothing (and so passes) in a project that never constructs it directly.
   */
  public static ArchRule illegalStateTransitionsShouldOnlyComeFromTransitions() {
    return noClasses()
        .that()
        .doNotHaveFullyQualifiedName(Transitions.class.getName())
        .should()
        .callConstructor(IllegalStateTransitionException.class, Object.class, Object.class)
        .as("IllegalStateTransitionException should be raised only via Transitions.check")
        .because(
            "declaring the legal transitions in a Transitions table and routing every check "
                + "through it keeps the transition rules in one place and the exception uniform")
        .allowEmptyShould(true);
  }

  /**
   * An {@link ErrorCode} implementation is an enum, so a bounded context's error codes form one
   * enumerated catalogue in a single place, as the {@code ErrorCode} contract intends. Matches only
   * named (or anonymous) classes that declare {@code implements ErrorCode}; the inline {@code () ->
   * "code"} lambda shorthand used for a one-off code compiles to an {@code invokedynamic} target
   * with no such class and is not matched, so it stays allowed. Part of {@link #all()}; matches
   * nothing (and so passes) in a project that declares no named {@code ErrorCode} type.
   */
  public static ArchRule errorCodesShouldBeEnums() {
    return classes()
        .that()
        .implement(ErrorCode.class)
        .should()
        .beAssignableTo(Enum.class)
        .as("ErrorCode implementations should be enums")
        .because(
            "modelling a context's error codes as an enum keeps the catalogue in one place, "
                + "rather than scattering ad-hoc classes")
        .allowEmptyShould(true);
  }

  /**
   * A repository port — a type carrying the core {@link Repository @Repository} — is an interface
   * that resides in the domain layer. A repository is the collection-like abstraction over an
   * aggregate, so the port is a domain concept (an interface the domain owns), while its technical
   * implementation lives in the infrastructure layer (see {@link
   * #repositoryImplementationsShouldResideInInfrastructure()}). Matches the core
   * {@code @Repository} annotation, not Spring's stereotype, so a Spring {@code @Repository} on an
   * implementation class is unaffected. Part of {@link #all()}; matches nothing (and so passes) in
   * a project that declares no repository ports.
   */
  public static ArchRule repositoryPortsShouldBeInterfacesInDomain() {
    return classes()
        .that()
        .areAnnotatedWith(Repository.class)
        .should()
        .beInterfaces()
        .andShould()
        .resideInAPackage("..domain..")
        .as("@Repository ports should be interfaces residing in the domain layer")
        .because(
            "a repository is a collection-like abstraction the domain owns, so the port is a "
                + "domain interface, while its technical implementation lives in infrastructure")
        .allowEmptyShould(true);
  }

  /**
   * A repository implementation — a concrete class implementing a domain {@link
   * Repository @Repository} port — resides in the infrastructure layer. The port is the
   * domain-owned abstraction; the class that fulfils it with a concrete persistence technology is
   * an outbound adapter and belongs in infrastructure. Part of {@link #all()}; matches nothing (and
   * so passes) in a project with no repository implementations.
   */
  public static ArchRule repositoryImplementationsShouldResideInInfrastructure() {
    return classes()
        .that(implementARepositoryPort())
        .should()
        .resideInAPackage("..infrastructure..")
        .as("repository implementations should reside in the infrastructure layer")
        .because(
            "the class that fulfils a domain repository port with a concrete persistence "
                + "technology is an outbound adapter, which belongs in infrastructure")
        .allowEmptyShould(true);
  }

  /**
   * A repository implementation carries Spring's {@code @Repository} stereotype (matched by name,
   * see {@link #SPRING_REPOSITORY}) rather than a bare {@code @Component}. As a specialization of
   * {@code @Component} it is component-scanned identically, but it also names the adapter's role
   * precisely and enables Spring's persistence-exception translation. Deliberately
   * <strong>not</strong> part of {@link #all()}: it presumes Spring, so a non-Spring project's
   * implementations — which carry no such annotation — would fail it rather than pass vacuously.
   * Adopt it in Spring projects alongside {@link #all()}:
   *
   * <pre>{@code
   * @ArchTest static final ArchRule repos = AiPersimmonDddRules.repositoryImplementationsShouldBeSpringRepositories();
   * }</pre>
   */
  public static ArchRule repositoryImplementationsShouldBeSpringRepositories() {
    return classes()
        .that(implementARepositoryPort())
        .should()
        .beAnnotatedWith(SPRING_REPOSITORY)
        .orShould()
        .beMetaAnnotatedWith(SPRING_REPOSITORY)
        .as("repository implementations should be annotated with Spring's @Repository")
        .because(
            "Spring's @Repository names the persistence adapter's role and enables "
                + "persistence-exception translation, which a bare @Component does not")
        .allowEmptyShould(true);
  }

  /**
   * An {@link IntegrationEvent} — a fact one bounded context publishes for others — resides in the
   * context's {@code ..api..} package, its published contract. It is the mirror of {@link
   * #domainEventsShouldStayInDomain()}: a domain event stays private to the domain, while an
   * integration event is deliberately exposed, so it lives with the rest of the outward contract
   * rather than in the domain, application, or adapter internals. Deliberately <strong>not</strong>
   * part of {@link #all()}: it presumes the {@code ..api..} published-contract convention (used by
   * the modulith and multi-module layouts); a layout that publishes contracts elsewhere — for
   * example a shared {@code contracts} module in a microservice split — would adopt its own
   * variant. Adopt it alongside {@link #all()}:
   *
   * <pre>{@code
   * @ArchTest static final ArchRule events = AiPersimmonDddRules.integrationEventsShouldResideInApi();
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
   * Each bounded context under {@code basePackage} depends on another context only through that
   * context's {@code ..api..} package — never by reaching into its domain, application,
   * infrastructure, or adapter internals. A context is the first package segment under {@code
   * basePackage} (so under {@code "com.example"} the contexts are {@code com.example.ordering},
   * {@code com.example.inventory}, …), and its {@code ..api..} package is its published contract;
   * everything else is private to it. This is the multi-context isolation rule that keeps the
   * "published language" boundary honest.
   *
   * <p>Parameterised on {@code basePackage}, so it is <strong>not</strong> part of the
   * parameterless {@link #all()}; wire it into a test that also scopes which classes are analysed.
   * Only analysed classes are checked, so keep the composition root (which legitimately wires every
   * context together) out of {@code @AnalyzeClasses}, or it will be reported. A class that sits
   * directly in {@code basePackage} (an application root, with no context segment) is skipped.
   *
   * <pre>{@code
   * @ArchTest static final ArchRule contexts =
   *         AiPersimmonDddRules.boundedContextsShouldOnlyDependOnEachOthersApi("com.example");
   * }</pre>
   *
   * @param basePackage the package under which each immediate sub-package is a context
   */
  public static ArchRule boundedContextsShouldOnlyDependOnEachOthersApi(String basePackage) {
    return classes()
        .that()
        .resideInAPackage(basePackage + "..")
        .should(dependOnOtherContextsOnlyThroughApi(basePackage))
        .as("bounded contexts should depend on each other only through their ..api.. packages")
        .because(
            "a context's internals are private; only its ..api.. package is the published "
                + "contract that other contexts may depend on")
        .allowEmptyShould(true);
  }

  /**
   * A concrete class (not an interface) that implements, directly or transitively, an interface
   * annotated with the core {@link Repository @Repository} — i.e. a repository implementation.
   * Excludes the port interfaces themselves, which carry the annotation but do not
   * <em>implement</em> it.
   */
  private static DescribedPredicate<JavaClass> implementARepositoryPort() {
    return DescribedPredicate.describe(
        "implement a @Repository port",
        javaClass ->
            !javaClass.isInterface()
                && javaClass.getAllRawInterfaces().stream()
                    .anyMatch(anInterface -> anInterface.isAnnotatedWith(Repository.class)));
  }

  /**
   * Reports a violation for each dependency whose target lives in a <em>different</em> bounded
   * context (a different first segment under {@code basePackage}) and is not in that context's
   * {@code ..api..} package. Dependencies within the same context, on the target context's {@code
   * ..api..}, or on anything outside {@code basePackage} (the JDK, frameworks, shared kernel) are
   * allowed.
   */
  private static ArchCondition<JavaClass> dependOnOtherContextsOnlyThroughApi(String basePackage) {
    String prefix = basePackage.endsWith(".") ? basePackage : basePackage + ".";
    return new ArchCondition<>(
        "depend on other bounded contexts only through their ..api.. packages") {
      @Override
      public void check(JavaClass origin, ConditionEvents events) {
        String originContext = contextSegment(origin.getName(), prefix);
        if (originContext == null) {
          return;
        }
        origin
            .getDirectDependenciesFromSelf()
            .forEach(
                dependency -> {
                  JavaClass target = dependency.getTargetClass();
                  String targetContext = contextSegment(target.getName(), prefix);
                  if (targetContext == null || targetContext.equals(originContext)) {
                    return;
                  }
                  String apiPackage = prefix + targetContext + ".api";
                  String targetPackage = target.getPackageName();
                  boolean throughApi =
                      targetPackage.equals(apiPackage)
                          || targetPackage.startsWith(apiPackage + ".");
                  if (!throughApi) {
                    events.add(
                        SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                  }
                });
      }
    };
  }

  /**
   * The bounded-context segment of {@code className}: the first package segment after {@code
   * prefix}, or {@code null} when the class does not live under {@code prefix} or sits directly in
   * it (no context segment).
   */
  private static String contextSegment(String className, String prefix) {
    if (!className.startsWith(prefix)) {
      return null;
    }
    String remainder = className.substring(prefix.length());
    int dot = remainder.indexOf('.');
    return dot < 0 ? null : remainder.substring(0, dot);
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

  /** All of the above, combined into a single rule. */
  public static ArchRule all() {
    return CompositeArchRule.of(domainShouldNotDependOnOuterLayers())
        .and(applicationShouldNotDependOnInfrastructureOrInterface())
        .and(domainShouldBeFrameworkFree())
        .and(domainEventsShouldStayInDomain())
        .and(domainEventListenersShouldResideInApplicationOrDomain())
        .and(integrationEventListenersShouldResideInAdapter())
        .and(domainEventListenersShouldBeAnnotatedWithDomainEventHandler())
        .and(integrationEventsShouldDeclareEventType())
        .and(commandHandlersShouldNotDependOnOtherCommandHandlers())
        .and(commandHandlersAndApplicationShouldNotCallSendAs())
        .and(invariantsShouldResideInDomain())
        .and(invariantViolationsShouldOnlyComeFromCheckInvariant())
        .and(invariantsShouldNotBeSpringComponents())
        .and(domainBuildingBlocksShouldResideInDomain())
        .and(domainServicesShouldResideInDomain())
        .and(aggregateRootsShouldExtendAbstractAggregateRoot())
        .and(valueObjectsShouldBeImmutable())
        .and(illegalStateTransitionsShouldOnlyComeFromTransitions())
        .and(errorCodesShouldBeEnums())
        .and(repositoryPortsShouldBeInterfacesInDomain())
        .and(repositoryImplementationsShouldResideInInfrastructure());
  }
}

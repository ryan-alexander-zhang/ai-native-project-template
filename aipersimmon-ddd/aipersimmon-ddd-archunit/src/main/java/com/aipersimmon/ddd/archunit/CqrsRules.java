package com.aipersimmon.ddd.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.annotation.Entity;
import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.aipersimmon.ddd.cqrs.Query;
import com.aipersimmon.ddd.cqrs.QueryHandler;
import com.aipersimmon.ddd.cqrs.ReadModel;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * CQRS and application-layer rules: how command and query handlers relate to one another and to the
 * command bus, and where the CQRS handlers live. All three are bundled into {@link
 * AiPersimmonDddRules#all()}.
 */
public final class CqrsRules {

  private CqrsRules() {}

  /**
   * A {@link CommandHandler} implementation must not depend on another {@link CommandHandler}
   * implementation. A command handler is an entry point on the command bus, not an internal API:
   * one handler invoking another either bypasses the callee's {@code CommandInterceptor} chain (its
   * transaction, validation, logging) or, if routed back through the bus, nests transactions and
   * double-applies those concerns; it also blurs the unit-of-work boundary and couples two use
   * cases that should evolve independently. Reusable logic belongs in a domain service or a
   * non-handler application collaborator, injected into both handlers. Part of {@link
   * AiPersimmonDddRules#all()}; matches nothing (and so passes) in a project that has no command
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
   * Command handlers and application code must not call {@code CommandBus.sendAs(..)}.
   *
   * <p>{@code sendAs} is the durable-runtime / outbox staged-dispatch entry point: it replays a
   * command under a message identity that was already minted and persisted upstream (a Process
   * Manager effect row, an outbox row), using that identity verbatim. It exists so an at-least-once
   * relay can redeliver the same effect under a stable messageId. A handler or application class
   * calling it would fabricate message identity outside the sanctioned minting authorities and
   * bypass the causation chain. Business dispatch uses {@link CommandBus#send(Command)} / {@code
   * send(Command, CommandContext)}.
   *
   * <p>Passes vacuously until {@code sendAs} and a violating call site exist; framework-agnostic,
   * so it is safe in {@link AiPersimmonDddRules#all()}.
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

  /**
   * A {@link CommandHandler} or {@link QueryHandler} implementation resides in the application
   * layer. A handler orchestrates one unit of work — driving the domain through its ports to run a
   * command, or reading a read model to answer a query — which is application-layer responsibility,
   * not domain, infrastructure, or interface work; putting one in an adapter, for instance, lets
   * the boundary do orchestration it should merely delegate.
   *
   * <p>The mirror of {@link BuildingBlockRules#domainBuildingBlocksShouldResideInDomain()} for the
   * write/read side: that pins the domain building blocks to the domain, this pins the CQRS
   * handlers to the application layer. Matched by the {@code CommandHandler} / {@code QueryHandler}
   * interfaces, which are on every module's classpath, so a handler can be declared anywhere and
   * only this rule keeps it in place — a Maven module split does not (the interfaces are visible
   * everywhere). Part of {@link AiPersimmonDddRules#all()}; matches nothing (and so passes) in a
   * project that has no handlers.
   */
  public static ArchRule commandAndQueryHandlersShouldResideInApplication() {
    return classes()
        .that()
        .implement(CommandHandler.class)
        .or()
        .implement(QueryHandler.class)
        .should()
        .resideInAPackage("..application..")
        .as("command and query handlers should reside in the application layer")
        .because(
            "a CommandHandler or QueryHandler orchestrates one unit of work over the domain, "
                + "which is an application-layer responsibility, not domain, infrastructure, or "
                + "interface work")
        .allowEmptyShould(true);
  }

  /**
   * Every reference-typed component of a {@link Command} implementation declares a Bean Validation
   * annotation — a constraint, or {@code @Valid} for a cascaded type.
   *
   * <p>The command bus promises a validation gate for <em>every</em> entry into the application —
   * event listeners and process-manager relays included, not just HTTP — but the gate only checks
   * what a command declares. Experience shows the declarations cluster on the commands a web
   * adapter binds and quietly stop at the internal ones, precisely the commands whose callers no
   * framework validates first; "which commands are validated" then becomes a question answered by
   * opening files one at a time. This rule turns the convention into a mechanism: a bare component
   * fails the build with its field name, not a code review with luck.
   *
   * <p>What the rule asks for is a <em>declaration</em>, not {@code @NotNull} everywhere. A
   * deliberately optional component states so with a null-tolerant constraint (every standard
   * constraint except the {@code @NotNull} family accepts null — {@code @Size}, {@code @Positive},
   * {@code @Pattern} all do), so optionality is written down instead of left indistinguishable from
   * an omission. Primitive components need nothing: they cannot be null, and any range they must
   * sit in is a real constraint the author adds because it is true, not to satisfy a rule.
   *
   * <p>Opt-in rather than part of {@link AiPersimmonDddRules#all()} because it presumes the project
   * validates commands with Bean Validation at all — true wherever the CQRS starter's validation
   * interceptor is on the bus, but not something the framework-agnostic bundle may assume. Matches
   * nothing (and so passes) in a project that has no commands.
   */
  public static ArchRule commandComponentsShouldDeclareValidationConstraints() {
    return classes()
        .that()
        .implement(Command.class)
        .should(declareAValidationAnnotationOnEveryReferenceTypedComponent())
        .as(
            "every reference-typed component of a command should declare a Bean Validation "
                + "annotation (a constraint, or @Valid for a cascaded type)")
        .because(
            "the bus validates every entry into the application, but only what a command "
                + "declares; an undeclared component makes 'is this required?' a question only "
                + "the handler's source can answer, and internal commands — the ones no web "
                + "framework validates first — are exactly where declarations go missing")
        .allowEmptyShould(true);
  }

  /**
   * A {@link Command} or {@link Query} type itself resides in the application layer, not only its
   * handler.
   *
   * <p>{@link #commandAndQueryHandlersShouldResideInApplication()} pins the handler and says
   * nothing about the message, which is the half that actually travels. A command is the
   * application's inward contract — the one shape an HTTP adapter, an event listener and a
   * process-manager effect all have to agree on — and where it is declared decides who may depend
   * on it without depending on anything else. Declared in the domain it becomes part of the model,
   * and the model starts knowing the names of its use cases; declared in an adapter it becomes that
   * adapter's private vocabulary, and the next caller either reaches into the adapter or writes a
   * second command that means the same thing.
   *
   * <p>{@link OperationLogRules#operationLogShouldOnlyAnnotateApplicationCommands()} already
   * presumes this — it requires the annotated command to be in {@code ..application..} — so until
   * now the convention was enforced only for commands that happened to be audited. Part of {@link
   * AiPersimmonDddRules#all()}; matches nothing (and so passes) in a project with no commands or
   * queries.
   */
  public static ArchRule commandsAndQueriesShouldResideInApplication() {
    return classes()
        .that()
        .implement(Command.class)
        .or()
        .implement(Query.class)
        .should()
        .resideInAPackage("..application..")
        .as("command and query types should reside in the application layer")
        .because(
            "a command is the application's inward contract, agreed on by every caller that "
                + "drives a use case; in the domain it makes the model aware of its use cases, and "
                + "in an adapter it becomes that adapter's private vocabulary")
        .allowEmptyShould(true);
  }

  /**
   * The read side returns projections, not the write model: a {@link ReadModel @ReadModel} type
   * resides in the application layer (or an {@code ..api..} published-contract package) and holds
   * no {@link AggregateRoot @AggregateRoot} or {@link Entity @Entity} anywhere in its fields.
   *
   * <p>A read model is a shape assembled for rendering. Letting an aggregate into it drags the
   * write model onto the read path: every row has to be rebuilt through the root's constructor,
   * running the invariants and loading whatever the aggregate's graph reaches, none of which the
   * answer needs. It is the reason list endpoints get slow in a way no index fixes, and it is
   * invisible in review because the code that does it reads perfectly naturally — the repository is
   * right there.
   *
   * <p>Domain <em>value objects</em> are deliberately allowed. A read model that carries a {@code
   * Money} or an {@code OrderStatus} borrows a word from the model's vocabulary at no cost: there
   * is no identity to load, no lifecycle to run, nothing to version-check. The line is drawn at
   * identity, which is exactly where the expense and the consistency boundary are.
   *
   * <p>Part of {@link AiPersimmonDddRules#all()}; matches nothing (and so passes) in a project that
   * annotates no read models.
   */
  public static ArchRule readModelsShouldBeProjectionShapes() {
    return CompositeArchRule.of(readModelsShouldResideInApplicationOrApi())
        .and(readModelsShouldNotHoldAggregatesOrEntities())
        .as(
            "@ReadModel types should reside in the application layer or a published ..api.. "
                + "package and should not hold aggregates or entities");
  }

  /**
   * One half of {@link #readModelsShouldBeProjectionShapes()}: a {@link ReadModel @ReadModel}
   * resides in {@code ..application..} — where the query that returns it and the projection that
   * fills it live — or in {@code ..api..} when it is a published query contract another context
   * reads, the same allowance {@link BuildingBlockRules#valueObjectsShouldResideInDomainOrApi()}
   * makes. Never in the domain (it is not part of the model), infrastructure or an adapter. Exposed
   * separately so a project can state that half on its own.
   */
  public static ArchRule readModelsShouldResideInApplicationOrApi() {
    return classes()
        .that()
        .areAnnotatedWith(ReadModel.class)
        .should()
        .resideInAnyPackage("..application..", "..api..")
        .as(
            "@ReadModel types should reside in the application layer or a published ..api.. package")
        .because(
            "a read model is a projection assembled for querying, so it belongs with the query "
                + "that returns it — or, when it is a published query contract, with the outward "
                + "contract; it is not part of the domain model")
        .allowEmptyShould(true);
  }

  /**
   * The other half of {@link #readModelsShouldBeProjectionShapes()}: no field of a {@link
   * ReadModel @ReadModel} involves an {@code @AggregateRoot} or {@code @Entity} type, generic
   * arguments included. Exposed separately so a project can state that half on its own.
   */
  public static ArchRule readModelsShouldNotHoldAggregatesOrEntities() {
    return classes()
        .that()
        .areAnnotatedWith(ReadModel.class)
        .should(notHoldTheWriteModel("@ReadModel"))
        .as("@ReadModel types should not hold aggregates or entities")
        .because(
            "a read model exists so a query can be answered without rebuilding the write model; "
                + "an aggregate inside one puts the constructor, the invariants and the "
                + "aggregate's graph back on the read path")
        .allowEmptyShould(true);
  }

  /**
   * A {@link Query} answers with a projection: the {@code R} in {@code Query<R>} is not an {@link
   * AggregateRoot @AggregateRoot} or {@link Entity @Entity} type, and neither is anything inside it
   * — {@code Query<Optional<Order>>}, {@code Query<List<Order>>} and {@code Query<Slice<Order>>}
   * are reported as readily as {@code Query<Order>}.
   *
   * <p>The other end of {@link #readModelsShouldBeProjectionShapes()}, and the one that actually
   * closes the door: that rule constrains the types a project chose to mark, while this one reads
   * the declared result of every query whether it was marked or not. Handing an aggregate back to a
   * caller publishes the write model as the read contract — the caller now depends on the shape of
   * the root, so the root can no longer change without breaking it, and the caller can invoke
   * behaviour on it outside any transaction or unit of work.
   *
   * <p>Loading an aggregate <em>inside</em> a query handler stays allowed, as {@link Query}'s own
   * documentation says it may be for a single-entity read; what this rule refuses is returning it.
   * Part of {@link AiPersimmonDddRules#all()}; matches nothing (and so passes) in a project with no
   * queries.
   */
  public static ArchRule queryResultsShouldNotBeAggregatesOrEntities() {
    return classes()
        .that()
        .implement(Query.class)
        .should(declareAResultThatIsNotTheWriteModel())
        .as("query result types should not be aggregates or entities")
        .because(
            "returning a root publishes the write model as the read contract: the caller depends "
                + "on the aggregate's shape and can invoke its behaviour outside any unit of work")
        .allowEmptyShould(true);
  }

  /**
   * Reports a violation for each field of the checked type whose signature involves an
   * {@code @AggregateRoot} or {@code @Entity} type, generic arguments included.
   *
   * @param role how to name the checked type in the message
   */
  private static ArchCondition<JavaClass> notHoldTheWriteModel(String role) {
    return new ArchCondition<>("not hold an @AggregateRoot or @Entity") {
      @Override
      public void check(JavaClass readModel, ConditionEvents events) {
        readModel.getFields().stream()
            .filter(field -> !field.getModifiers().contains(JavaModifier.STATIC))
            .filter(field -> !field.getModifiers().contains(JavaModifier.SYNTHETIC))
            .forEach(
                field ->
                    field.getType().getAllInvolvedRawTypes().stream()
                        .filter(CqrsRules::isWriteModel)
                        .forEach(
                            held ->
                                events.add(
                                    SimpleConditionEvent.violated(
                                        field,
                                        field.getDescription()
                                            + " puts the write model "
                                            + held.getName()
                                            + " inside a "
                                            + role
                                            + " — project the fields the answer needs instead"))));
      }
    };
  }

  /**
   * Reports a violation for a {@link Query} whose declared result type involves an
   * {@code @AggregateRoot} or {@code @Entity}. A query that does not parameterise {@code Query} at
   * all (raw, or through a further-generic supertype) has no declared result to read and is left
   * alone rather than guessed at.
   */
  private static ArchCondition<JavaClass> declareAResultThatIsNotTheWriteModel() {
    return new ArchCondition<>("declare a result type that is not an @AggregateRoot or @Entity") {
      @Override
      public void check(JavaClass query, ConditionEvents events) {
        resultTypesOf(query).stream()
            .filter(CqrsRules::isWriteModel)
            .forEach(
                root ->
                    events.add(
                        SimpleConditionEvent.violated(
                            query,
                            query.getFullName()
                                + " answers with the write model "
                                + root.getName()
                                + " — answer with a @ReadModel projection instead")));
      }
    };
  }

  /**
   * Every raw type involved in the {@code R} of {@code Query<R>} for the given query, following the
   * interface hierarchy so a query declared through an intermediate interface ({@code interface
   * PagedQuery<T> extends Query<Slice<T>>}) is read at the point where {@code Query} is
   * parameterised.
   */
  private static Set<JavaClass> resultTypesOf(JavaClass query) {
    Set<JavaClass> resultTypes = new LinkedHashSet<>();
    collectResultTypes(query, resultTypes, new LinkedHashSet<>());
    return resultTypes;
  }

  private static void collectResultTypes(
      JavaClass type, Set<JavaClass> resultTypes, Set<String> visited) {
    if (!visited.add(type.getName())) {
      return;
    }
    for (JavaType implemented : type.getInterfaces()) {
      if (implemented.toErasure().isEquivalentTo(Query.class)
          && implemented instanceof JavaParameterizedType parameterized
          && parameterized.getActualTypeArguments().size() == 1) {
        resultTypes.addAll(parameterized.getActualTypeArguments().get(0).getAllInvolvedRawTypes());
      }
      collectResultTypes(implemented.toErasure(), resultTypes, visited);
    }
    type.getRawSuperclass()
        .ifPresent(superclass -> collectResultTypes(superclass, resultTypes, visited));
  }

  /** A write-model type: one carrying {@code @AggregateRoot} or {@code @Entity}. */
  private static boolean isWriteModel(JavaClass javaClass) {
    return javaClass.isAnnotatedWith(AggregateRoot.class)
        || javaClass.isAnnotatedWith(Entity.class);
  }

  private static ArchCondition<JavaClass>
      declareAValidationAnnotationOnEveryReferenceTypedComponent() {
    return new ArchCondition<>(
        "declare a Bean Validation annotation on every reference-typed component") {
      @Override
      public void check(JavaClass command, ConditionEvents events) {
        command.getFields().stream()
            .filter(field -> !field.getModifiers().contains(JavaModifier.STATIC))
            .filter(field -> !field.getModifiers().contains(JavaModifier.SYNTHETIC))
            .filter(field -> !field.getRawType().isPrimitive())
            .filter(
                field ->
                    field.getAnnotations().stream()
                        .noneMatch(annotation -> declaresValidation(annotation.getRawType())))
            .forEach(
                field ->
                    events.add(
                        SimpleConditionEvent.violated(
                            field,
                            field.getDescription()
                                + " declares no Bean Validation annotation — required gets a "
                                + "@NotNull-family constraint, deliberately optional gets a "
                                + "null-tolerant one, a cascaded type gets @Valid")));
      }
    };
  }

  /**
   * A Bean Validation declaration: {@code @Valid}, or any annotation carrying {@code @Constraint}
   * (directly for the standard ones, possibly meta for composed custom constraints). Matched by
   * name so this jar never depends on jakarta.validation.
   */
  private static boolean declaresValidation(JavaClass annotationType) {
    return annotationType.getName().equals("jakarta.validation.Valid")
        || annotationType.isAnnotatedWith("jakarta.validation.Constraint")
        || annotationType.isMetaAnnotatedWith("jakarta.validation.Constraint");
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

  /**
   * Reports a violation for each dependency whose target is a {@link CommandHandler} implementation
   * other than the {@code CommandHandler} interface itself and other than the origin class.
   * Excluding the interface keeps a handler's own {@code implements CommandHandler} from counting;
   * excluding the origin keeps a self-reference from counting. Used with {@code
   * classes().should(...)}, so a {@code violated} event is a rule violation.
   */
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
}

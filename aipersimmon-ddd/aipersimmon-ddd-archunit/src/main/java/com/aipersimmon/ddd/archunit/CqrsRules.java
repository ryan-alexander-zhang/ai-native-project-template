package com.aipersimmon.ddd.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.aipersimmon.ddd.cqrs.QueryHandler;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

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

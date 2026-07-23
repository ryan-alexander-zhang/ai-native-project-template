package com.aipersimmon.ddd.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.aipersimmon.ddd.cqrs.Command;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Operation Log rules: how a consuming application may reach for the Operation Log component. Both
 * are bundled into {@link AiPersimmonDddRules#all()} and both tolerate an empty match, so a project
 * that does not use the component passes them vacuously.
 *
 * <p>Like the event-listener rules, these match the component by <em>name</em> — the
 * {@code @OperationLog} annotation and the {@code com.aipersimmon.ddd.operationlog..} packages by
 * string — so this rules jar carries no compile dependency on the Operation Log component and does
 * not drag it onto a consumer's classpath. (The test fixtures do depend on it, at test scope.)
 */
public final class OperationLogRules {

  /**
   * The Operation Log component's root package. Every module of the component ({@code
   * -operation-log}, {@code -engine}, {@code -jdbc}, {@code -cqrs-spring}, {@code -mybatis-plus})
   * lives under it, so a single package match covers the whole component regardless of which
   * backend a project wires in.
   */
  private static final String OPERATION_LOG_PACKAGE = "com.aipersimmon.ddd.operationlog..";

  /** The metadata annotation, matched by fully-qualified name to avoid a compile dependency. */
  private static final String OPERATION_LOG_ANNOTATION =
      "com.aipersimmon.ddd.operationlog.annotation.OperationLog";

  private OperationLogRules() {}

  /**
   * The domain layer must not depend on the Operation Log component. Operation logging is an
   * application-layer cross-cutting concern captured around the command boundary (an interceptor, a
   * hand-written {@code OperationLogDefinition}); the pure domain records intent through its own
   * aggregates and domain events and stays unaware that its use cases are being audited. A domain
   * type reaching for the {@code @OperationLog} annotation, an {@code OperationLogs} port, or any
   * other component type couples the model to an infrastructural concern it must not know about.
   *
   * <p>Part of {@link AiPersimmonDddRules#all()}; matches nothing (and so passes) in a project that
   * does not use the Operation Log component.
   */
  public static ArchRule domainShouldNotDependOnOperationLog() {
    return noClasses()
        .that()
        .resideInAPackage("..domain..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage(OPERATION_LOG_PACKAGE)
        .as("domain classes should not depend on the Operation Log component")
        .because(
            "operation logging is an application-layer cross-cutting concern captured around the "
                + "command boundary; the domain records intent through its own aggregates and "
                + "events and must stay unaware that its use cases are audited")
        .allowEmptyShould(true);
  }

  /**
   * The {@code @OperationLog} annotation must sit only on an application-layer {@link Command}
   * type. The annotation is additive metadata on a command — the compiler turns it into a
   * synthesized {@code OperationLogDefinition} whose templates project over the command and its
   * result — so it is meaningful only where a command is dispatched through the bus and its
   * interceptor chain runs. Putting it on a non-command type (a handler, a domain object, a DTO) or
   * on a command that lives outside the application layer produces metadata nothing captures.
   *
   * <p>Matches the annotation by name, so it is safe in {@link AiPersimmonDddRules#all()} and
   * passes vacuously where the annotation is unused.
   */
  public static ArchRule operationLogShouldOnlyAnnotateApplicationCommands() {
    return classes()
        .that()
        .areAnnotatedWith(OPERATION_LOG_ANNOTATION)
        .should()
        .implement(Command.class)
        .andShould()
        .resideInAPackage("..application..")
        .as("@OperationLog should only annotate application-layer Command types")
        .because(
            "the annotation compiles into a definition that projects over a command and its "
                + "result and is captured only around the command-bus boundary, so it is "
                + "meaningless on a non-command type or on a command outside the application layer")
        .allowEmptyShould(true);
  }
}

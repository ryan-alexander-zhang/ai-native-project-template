package com.example;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.aipersimmon.ddd.archunit.AiPersimmonDddRules;
import com.aipersimmon.ddd.archunit.BoundedContextRules;
import com.aipersimmon.ddd.archunit.CqrsRules;
import com.aipersimmon.ddd.archunit.EventRules;
import com.aipersimmon.ddd.archunit.LayeringRules;
import com.aipersimmon.ddd.archunit.RepositoryRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Verifies every bounded context against the reusable DDD layering rules. ArchUnit imports the
 * compiled main classes of the whole application (every module is on this module's classpath) and
 * checks the rules over them.
 *
 * <p>The scope is the base package {@code com.example}, not an enumerated list of contexts, so a
 * new context (or a new module within one) is covered the moment it is added — nothing here needs
 * editing. Tests are excluded ({@link ImportOption.DoNotIncludeTests}); the composition root's own
 * classes sit directly in {@code com.example} (no context segment), which the context-isolation
 * rule skips, so the root can legitimately wire every context together without tripping it.
 */
@AnalyzeClasses(
    packages = "com.example",
    importOptions = {ImportOption.DoNotIncludeTests.class})
class ArchitectureTest {

  @ArchTest static final ArchRule ddd = AiPersimmonDddRules.all();

  /**
   * Spring-specific companion to {@code all()}: every persistence adapter implementing a domain
   * {@code @Repository} port carries Spring's {@code @Repository} stereotype (not a bare
   * {@code @Component}), so it names its role and gets persistence-exception translation. Opt-in
   * because it presumes Spring, which the framework-free {@code all()} bundle does not.
   */
  @ArchTest
  static final ArchRule repositoryImplementations =
      RepositoryRules.implementationsShouldBeSpringRepositories();

  /**
   * Integration events — the facts each context publishes for others — live in that context's
   * {@code ..api..} package, its published contract. Opt-in because it presumes the {@code ..api..}
   * convention this layout uses.
   */
  @ArchTest
  static final ArchRule integrationEvents = EventRules.integrationEventsShouldResideInApi();

  /**
   * Every context depends on another only through that context's {@code ..api..} package, never by
   * reaching into its domain, application, infrastructure, or adapter internals. The composition
   * root, which legitimately wires the contexts together, sits at the {@code com.example} root with
   * no context segment and is skipped by this rule.
   */
  @ArchTest
  static final ArchRule contextsAreIsolated =
      BoundedContextRules.dependOnEachOtherOnlyThroughApi("com.example");

  /**
   * No inbound adapter depends on a domain directly. An inbound adapter translates a transport
   * (HTTP, a cross-context integration event) into a command or query; a domain-event subscriber
   * belongs in the application layer, not here (see
   * decision-00008-event-subscriber-layer-placement). This stricter, opt-in hexagonal rule holds
   * because every context keeps its persistence adapters in a separate {@code *-infrastructure}
   * module, so no {@code ..adapter..} class needs the domain.
   */
  @ArchTest
  static final ArchRule adaptersDoNotDependOnDomain =
      LayeringRules.adapterShouldNotDependOnDomain();

  /**
   * Every reference-typed component of every command declares its Bean Validation contract
   * (issue-00148). The bus's validation gate guards each entry into the application — including the
   * internal commands only a relay or an event listener ever sends — but it checks only what a
   * command declares, and the declarations were found clustered on the two HTTP-bound commands
   * while eight internal ones said nothing. Opt-in because it presumes Bean Validation, which this
   * application's bus uses.
   */
  @ArchTest
  static final ArchRule commandsDeclareTheirValidationContract =
      CqrsRules.commandComponentsShouldDeclareValidationConstraints();

  /**
   * A SKU inside a domain is a {@code Sku}, never a {@code String} (issue-00085). Both contexts
   * model one, separately and on purpose, and this keeps the next line-carrying type from quietly
   * regressing to a string that no validation and no type check protects.
   *
   * <p>Narrowed to {@code sku} deliberately. The obvious generalisation — no domain field named
   * {@code *Id} or {@code *Code} may be a String — is wrong here, and the counterexamples are in
   * this repository: {@code ReservationFailureRef.reasonCode} and {@code
   * PaymentDeclineRef.declineCode} carry another context's opaque codes, and inventing a type for a
   * value ordering neither defines nor interprets would be modelling for its own sake. A rule that
   * has to be suppressed on its first three encounters is not a rule.
   */
  @ArchTest
  static final ArchRule skuIsAValueObjectInEveryDomain =
      noFields()
          .that()
          .areDeclaredInClassesThat()
          .resideInAPackage("..domain..")
          .and()
          .haveName("sku")
          .should()
          .haveRawType(String.class)
          .because(
              "a SKU is a value object in both contexts, so its validation is written once and the"
                  + " type system can tell it from any other string");
}

package com.example;

import com.aipersimmon.ddd.archunit.AiPersimmonDddRules;
import com.aipersimmon.ddd.archunit.BoundedContextRules;
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
      RepositoryRules.repositoryImplementationsShouldBeSpringRepositories();

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
      BoundedContextRules.boundedContextsShouldOnlyDependOnEachOthersApi("com.example");

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
}

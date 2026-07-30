package com.aipersimmon.ddd.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The library's own contract modules — the ones an application's inner tiers compile against — must
 * stay free of the frameworks the outer modules use. This is checked against the modules'
 * <em>bytecode</em>, not their POMs: a POM says what was declared, while bytecode says what is
 * actually reached, and it is the reach that lands on a consumer's classpath.
 *
 * <p>{@link LayeringRules#domainShouldBeFrameworkFree()} does not cover this. That rule matches a
 * package segment ({@code ..domain..}) so it can serve a consumer's own layout, and these modules
 * have no such segment — so the library's claim about its own contract modules was the one nothing
 * checked.
 *
 * <p>The allowlist is deliberately short and each entry is argued. Adding to it is a decision about
 * what every consumer of these modules has to carry, so it should take an argument, which is why
 * this is an allowlist rather than a list of banned frameworks: a new dependency fails the build
 * whether or not anyone thought to ban it in advance.
 */
class ContractModulesCarryNoFrameworkTest {

  /**
   * Everything a contract module may reach outside the JDK and its sibling modules.
   *
   * <ul>
   *   <li>{@code org.slf4j} — a logging <em>facade</em>, which is the one thing whose whole purpose
   *       is to impose no implementation on whoever depends on it. Reached by the outbox's logging
   *       dispatcher.
   *   <li>{@code com.fasterxml.jackson.core} — one exception type, {@code JsonProcessingException},
   *       which the outbox's default failure classifier calls permanent. That judgement is the
   *       classifier's to make, and matching the exception by type rather than by name is what
   *       keeps it from misfiring on a same-named class from elsewhere — a mistake this library has
   *       made before.
   * </ul>
   */
  /** The root package of every module this check is meant to cover, one per contract module. */
  private static final List<String> EVERY_CONTRACT_MODULE =
      List.of(
          "com.aipersimmon.ddd.core",
          "com.aipersimmon.ddd.application",
          "com.aipersimmon.ddd.integration",
          "com.aipersimmon.ddd.cqrs",
          "com.aipersimmon.ddd.tenancy",
          "com.aipersimmon.ddd.observability",
          "com.aipersimmon.ddd.inbox",
          "com.aipersimmon.ddd.outbox",
          "com.aipersimmon.ddd.operationlog",
          "com.aipersimmon.ddd.processmanager",
          "com.aipersimmon.ddd.web");

  private static final String[] ALLOWED_OUTSIDE_THE_JDK = {
    "com.aipersimmon.ddd..", "java..", "javax..", "org.slf4j..", "com.fasterxml.jackson.core..",
  };

  /**
   * Read from this module's test classpath rather than from {@code target/} directories, so the
   * reactor's own dependency ordering guarantees the modules are built — and so a module that is
   * removed from the POM is noticed here rather than silently skipped. Only the contract modules
   * are on that classpath; an engine, backend or starter is not, which is what scopes this check.
   */
  private final JavaClasses contractModules =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.aipersimmon.ddd");

  @Test
  void aContractModuleReachesNothingButTheJdkItsSiblingsAndAShortArguedAllowlist() {
    ArchRule rule =
        noClasses()
            .that()
            // This module itself is on the same classpath but is not a contract module: it is a
            // test-scope helper, and ArchUnit is exactly the framework it is supposed to carry.
            .resideOutsideOfPackage("com.aipersimmon.ddd.archunit..")
            .should()
            .dependOnClassesThat()
            .resideOutsideOfPackages(ALLOWED_OUTSIDE_THE_JDK)
            .as("contract modules should depend on nothing outside the JDK and their siblings")
            .because(
                "these are the modules an application's domain and application tiers compile "
                    + "against, so anything they reach is imposed on every consumer. Adding an "
                    + "entry to ALLOWED_OUTSIDE_THE_JDK is that decision, made deliberately");

    rule.check(contractModules);
  }

  /**
   * A rule over nothing passes. The check above is scoped by what happens to be on the test
   * classpath, so dropping a module from this POM would quietly stop checking it rather than fail —
   * the same silent shrink that let these modules go unchecked in the first place.
   */
  @Test
  void everyContractModuleIsActuallyOnTheClasspathBeingChecked() {
    List<String> unchecked =
        EVERY_CONTRACT_MODULE.stream().filter(module -> classesIn(module) == 0).toList();

    assertEquals(
        List.of(),
        unchecked,
        "these contract modules contributed no classes to the import, so the rule above never "
            + "looked at them — a rule over nothing passes, which is the same silent shrink that "
            + "left these modules unchecked in the first place");
  }

  private long classesIn(String rootPackage) {
    return contractModules.stream()
        .filter(javaClass -> javaClass.getPackageName().startsWith(rootPackage))
        .count();
  }
}

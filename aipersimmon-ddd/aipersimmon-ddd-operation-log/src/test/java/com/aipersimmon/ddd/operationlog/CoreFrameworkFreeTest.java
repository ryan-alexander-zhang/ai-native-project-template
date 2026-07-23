package com.aipersimmon.ddd.operationlog;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * Guards the hard rule stated in this module's POM: the Operation Log core ships free of Spring,
 * JDBC, CQRS, and ORM/JSON frameworks. The core is the framework-agnostic contract — model, ports,
 * SPI, the {@code @OperationLog} annotation — that the engine and backend adapters build on; a
 * framework leak here would force it onto every consumer. This is the T13 companion to {@code
 * OperationLogRules} (which guards a <em>consumer's</em> domain): that inspects the consumer, this
 * inspects our own shipped bytecode, where a reusable rule cannot reach.
 */
class CoreFrameworkFreeTest {

  private static final JavaClasses CORE =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.aipersimmon.ddd.operationlog");

  private static final String[] FORBIDDEN_FRAMEWORKS = {
    "org.springframework..",
    "jakarta.persistence..",
    "javax.persistence..",
    "org.hibernate..",
    "org.apache.ibatis..",
    "com.baomidou..",
    "com.fasterxml.jackson..",
    "com.aipersimmon.ddd.cqrs..",
    "com.aipersimmon.ddd.application..",
  };

  @Test
  void coreDoesNotDependOnFrameworksOrCqrs() {
    noClasses()
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(FORBIDDEN_FRAMEWORKS)
        .as("the Operation Log core must not depend on Spring, JDBC/ORM, JSON, or CQRS frameworks")
        .because(
            "the core is the framework-agnostic contract the engine and backends build on; a "
                + "framework dependency here would leak onto every consumer of the component")
        .check(CORE);
  }
}

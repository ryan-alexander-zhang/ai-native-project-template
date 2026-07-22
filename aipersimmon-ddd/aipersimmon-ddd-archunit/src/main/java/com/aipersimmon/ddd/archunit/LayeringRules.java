package com.aipersimmon.ddd.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;

/**
 * Layer-dependency rules: the direction of dependencies between the domain, application,
 * infrastructure, and interface/adapter layers, plus the domain's freedom from technical
 * frameworks. Layers are matched by the package segment they live in ({@code ..domain..}, {@code
 * ..application..}, {@code ..infrastructure..}, {@code ..adapter..}), so the rules hold whether a
 * layer is a sub-package (single deployable) or its own module (multi-module build).
 *
 * <p>{@link #domainShouldNotDependOnOuterLayers()}, {@link
 * #applicationShouldNotDependOnInfrastructureOrInterface()}, {@link
 * #domainShouldBeFrameworkFree()}, and {@link #domainShouldNotDependOnApiDocumentation()} are
 * bundled into {@link AiPersimmonDddRules#all()}; {@link #adapterShouldNotDependOnDomain()} is
 * opt-in.
 */
public final class LayeringRules {

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
   * API-documentation frameworks (OpenAPI/Swagger) the domain layer must not touch. The domain is
   * the pure core and stays free of every transport and framework concern, documentation included.
   * The application and interface tiers are deliberately <em>not</em> covered: a CQRS read model is
   * a presentation-facing DTO, so annotating it (or a request/response DTO) with {@code @Schema} to
   * document the wire contract is legitimate — the common practice across DDD + CQRS + OpenAPI
   * codebases (e.g. eShopOnContainers).
   */
  private static final String[] API_DOCUMENTATION_PACKAGES = {
    "io.swagger..", "org.springdoc..",
  };

  private LayeringRules() {}

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

  /**
   * The domain layer must be free of API-documentation frameworks. The domain is the framework-free
   * core; OpenAPI/Swagger annotations are a transport/presentation concern that never belongs on an
   * aggregate, value object, or domain event. The application and interface tiers are deliberately
   * <em>not</em> covered — a CQRS read model is a presentation-facing projection, so documenting it
   * (and request/response DTOs) with {@code @Schema} is normal and allowed.
   */
  public static ArchRule domainShouldNotDependOnApiDocumentation() {
    return noClasses()
        .that()
        .resideInAPackage("..domain..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(API_DOCUMENTATION_PACKAGES)
        .as("domain classes should not depend on API-documentation frameworks (OpenAPI/Swagger)")
        .because(
            "the domain is the framework-free core; OpenAPI/Swagger annotations are a "
                + "transport/presentation concern for the application or interface tier, not for "
                + "domain types")
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
   * <p>Deliberately <strong>not</strong> part of {@link AiPersimmonDddRules#all()}: it forbids
   * <em>every</em> adapter&#8594;domain reference, which some layouts legitimately need — for
   * example a project that keeps its persistence adapters (repository implementations that map
   * aggregates) in the same module as its inbound adapters. Adopt it in projects that separate
   * persistence adapters out and want the tighter hexagonal discipline where every driving adapter
   * goes through the application layer, and add it to a test alongside {@link
   * AiPersimmonDddRules#all()}:
   *
   * <pre>{@code
   * @ArchTest static final ArchRule adapters = LayeringRules.adapterShouldNotDependOnDomain();
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
}

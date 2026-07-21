package com.aipersimmon.ddd.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.aipersimmon.ddd.core.annotation.Repository;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Repository rules: repository ports are domain-owned interfaces, their implementations are
 * outbound adapters in infrastructure, and (opt-in) those implementations carry Spring's
 * {@code @Repository} stereotype.
 *
 * <p>{@link #portsShouldBeInterfacesInDomain()} and {@link
 * #implementationsShouldResideInInfrastructure()} are bundled into {@link
 * AiPersimmonDddRules#all()}; {@link #implementationsShouldBeSpringRepositories()} is opt-in
 * because it presumes Spring.
 */
public final class RepositoryRules {

  /**
   * Spring's {@code @Repository} stereotype, matched by fully-qualified name so the Spring-specific
   * repository rule stays free of a compile dependency on Spring.
   */
  private static final String SPRING_REPOSITORY = "org.springframework.stereotype.Repository";

  private RepositoryRules() {}

  /**
   * A repository port — a type carrying the core {@link Repository @Repository} — is an interface
   * that resides in the domain layer. A repository is the collection-like abstraction over an
   * aggregate, so the port is a domain concept (an interface the domain owns), while its technical
   * implementation lives in the infrastructure layer (see {@link
   * #implementationsShouldResideInInfrastructure()}). Matches the core {@code @Repository}
   * annotation, not Spring's stereotype, so a Spring {@code @Repository} on an implementation class
   * is unaffected. Part of {@link AiPersimmonDddRules#all()}; matches nothing (and so passes) in a
   * project that declares no repository ports.
   */
  public static ArchRule portsShouldBeInterfacesInDomain() {
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
   * an outbound adapter and belongs in infrastructure. Part of {@link AiPersimmonDddRules#all()};
   * matches nothing (and so passes) in a project with no repository implementations.
   */
  public static ArchRule implementationsShouldResideInInfrastructure() {
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
   * <strong>not</strong> part of {@link AiPersimmonDddRules#all()}: it presumes Spring, so a
   * non-Spring project's implementations — which carry no such annotation — would fail it rather
   * than pass vacuously. Adopt it in Spring projects alongside {@link AiPersimmonDddRules#all()}:
   *
   * <pre>{@code
   * @ArchTest static final ArchRule repos = RepositoryRules.implementationsShouldBeSpringRepositories();
   * }</pre>
   */
  public static ArchRule implementationsShouldBeSpringRepositories() {
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
}

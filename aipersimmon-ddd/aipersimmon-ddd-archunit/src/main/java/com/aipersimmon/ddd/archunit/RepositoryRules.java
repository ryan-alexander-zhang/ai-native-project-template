package com.aipersimmon.ddd.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.aipersimmon.ddd.core.annotation.Repository;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Repository rules: repository ports are domain-owned interfaces, their implementations are
 * outbound adapters in infrastructure, and (opt-in) those implementations carry Spring's
 * {@code @Repository} stereotype.
 *
 * <p>{@link #portsShouldBeInterfacesInDomain()}, {@link
 * #implementationsShouldResideInInfrastructure()} and {@link
 * #portsShouldNotBeUsedByInboundAdapters()} are bundled into {@link AiPersimmonDddRules#all()};
 * {@link #implementationsShouldBeSpringRepositories()} is opt-in because it presumes Spring.
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
   * No inbound adapter depends on a repository port: nothing in {@code ..adapter..} or {@code
   * ..interfaces..} touches a type carrying the core {@link Repository @Repository}.
   *
   * <p>An inbound adapter's job is to turn a transport into a dispatch — parse the request, build
   * the command or query, hand it to the bus. Loading an aggregate itself skips everything the bus
   * was going to do around that work: the transaction never opens, so a two-step read is not
   * consistent; validation, prechecks and the operation log never run; concurrency conflicts arrive
   * as raw persistence exceptions instead of the translated 409; and the read has no handler, so
   * there is nowhere for the next requirement to go except further into the controller. The
   * endpoint that does it usually looks the tidiest of the lot, because assembling a response map
   * from an aggregate is genuinely less code than a query, a handler and a read model — right up
   * until the second caller needs the same answer.
   *
   * <p>Deliberately narrower than {@link LayeringRules#adapterShouldNotDependOnDomain()}, and
   * adoptable where that one is not. The broad rule forbids <em>every</em> adapter→domain
   * reference, which a project cannot take if it keeps persistence adapters beside its inbound
   * ones, or if its controllers legitimately build a domain identifier to put in a query. This one
   * forbids only the port, which is the reference that actually lets the boundary do the
   * application's work.
   *
   * <p>Part of {@link AiPersimmonDddRules#all()}, and it earned the place by being switched on: it
   * reported ten controllers across this project's own samples, every one of them a GET that loaded
   * an aggregate through the port and hand-mapped it into a response map. All ten now go through
   * the query bus, and the migration each one needed — a query, a handler and a {@code @ReadModel}
   * — is the whole of what adopting this rule costs a project that reads the same way.
   *
   * <p>Matches nothing (and so passes) in a project that declares no repository ports.
   */
  public static ArchRule portsShouldNotBeUsedByInboundAdapters() {
    return noClasses()
        .that()
        .resideInAnyPackage(Layers.INTERFACE_LAYER)
        .should()
        .dependOnClassesThat()
        .areAnnotatedWith(Repository.class)
        .as("inbound adapters should not depend on repository ports")
        .because(
            "an adapter that loads an aggregate itself runs outside the transaction, the "
                + "validation, the prechecks and the conflict translation the bus would have "
                + "wrapped around the same work, and leaves the answer with no handler to live in")
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

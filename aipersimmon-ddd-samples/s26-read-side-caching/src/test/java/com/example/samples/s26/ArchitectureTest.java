package com.example.samples.s26;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.aipersimmon.ddd.archunit.AiPersimmonDddRules;
import com.example.samples.s26.catalog.domain.Products;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** The layering rules, plus the one prohibition this scenario exists to make enforceable. */
@AnalyzeClasses(
    packages = "com.example.samples.s26",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  /**
   * The library bundle — which now covers this sample's two subscribers equally, and did not when the
   * sample was written.
   *
   * <p>{@code domainEventListenersShouldBeAnnotatedWithDomainEventHandler} and the two placement rules
   * built on the same predicate matched {@code @EventListener} only where it was <em>directly
   * present</em>, so {@code @TransactionalEventListener} — which carries it as a meta-annotation, and is
   * the form to use for anything that must not run before the commit — was invisible to all three. This
   * sample is what turned that up, and the measurement was the whole point: two subscribers in one file,
   * same events, same package, differing only in the annotation, and {@code all()} rejected the
   * {@code @EventListener} one while saying nothing about the other. Filed as issue-00166 and fixed in
   * the library with {@code isMetaAnnotatedWith}, so the meta-annotation-aware rule this file used to
   * restate locally is gone — {@code ddd} covers both spellings now.
   */
  @ArchTest static final ArchRule ddd = AiPersimmonDddRules.all();

  /**
   * The write repository does not speak to a cache.
   *
   * <p>This is {@code AggregateCacheTrapTest}'s finding turned into something the build enforces. A memoising
   * {@code Products} is four lines, passes every read-side test, keeps the database consistent, and makes a
   * rename report success while writing nothing. Nobody reviewing the diff would see that, so the rule is here
   * rather than in a comment.
   *
   * <p>Stated over the interface rather than over a class name, so it holds for whatever the implementation is
   * called and for any decorator someone adds later.
   */
  @ArchTest
  static final ArchRule theaggregateRepositoryIsNotCached =
      noClasses()
          .that()
          .areAssignableTo(Products.class)
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("org.springframework.data.redis..", "org.springframework.cache..")
          .as("an aggregate must not be served from a cache: version() is the database's fact, not the cache's")
          .allowEmptyShould(true);

  /**
   * Only the infrastructure layer knows what the cache is made of.
   *
   * <p>The policy — tenant-scoped keys, jittered expiry, single flight, evict-after-commit — is the part worth
   * keeping, and it is only portable if it never learned which store it was talking to. This is the rule that
   * keeps {@code QueryCache} a real boundary instead of a formality.
   */
  @ArchTest
  static final ArchRule redisStaysInInfrastructure =
      noClasses()
          .that()
          .resideInAnyPackage("..application..", "..domain..", "..interfaces..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("org.springframework.data.redis..")
          .as("the cache's policy must not know which store implements it")
          .allowEmptyShould(true);

  /**
   * The domain knows nothing about caching at all — not the port, not the settings, not the keys.
   *
   * <p>It announces what changed and stops there. A domain that evicted cache keys would have to be edited every
   * time a read model was added, and would have made the aggregate's behaviour depend on the read side's
   * performance work.
   */
  @ArchTest
  static final ArchRule thedomainKnowsNothingAboutTheCache =
      noClasses()
          .that()
          .resideInAPackage("..catalog.domain..")
          .should()
          .dependOnClassesThat()
          .haveSimpleNameStartingWith("Cache")
          .as("invalidation is the read side's problem, and the domain should not have heard of it")
          .allowEmptyShould(true);

}

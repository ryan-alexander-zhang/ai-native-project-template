package com.example.samples.s24;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.aipersimmon.ddd.archunit.AiPersimmonDddRules;
import com.aipersimmon.ddd.archunit.BoundedContextRules;
import com.aipersimmon.ddd.archunit.EventRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The rules, and for this scenario they <strong>are</strong> the deliverable.
 *
 * <p>Everything else in this sample is a worked example; the four rules below are what a team would copy into their own
 * service on the day they add a context. Two come from the library, four do not, and the gap between those two sets is
 * most of what this scenario has to say.
 *
 * <p>Worth stating what a rule buys that a document does not: the prose in the package-info files explains why the
 * coupons contract is four types. In two years the prose will still say four and the package will have eleven. The rules
 * are the part that survives.
 */
@AnalyzeClasses(
    packages = "com.example.samples.s24",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest static final ArchRule ddd = AiPersimmonDddRules.all();

  /**
   * The library's isolation rule: a context is entered through its {@code api} and nowhere else.
   *
   * <p>Parameterised on the base package, so it is deliberately not part of {@code all()} — a single-context service has
   * no use for it. This is the one line that has to be added on the day a service gains its second context, and adding it
   * later means adding it after the coupling has happened.
   *
   * <p>{@code S24Application} sits directly in the base package rather than in a sub-package, which is what keeps the
   * composition root out of this rule's way: the rule skips classes with no context segment. A root in {@code s24.boot}
   * would be a context called {@code boot} that depends on everything.
   */
  @ArchTest
  static final ArchRule contextsAreEnteredThroughTheirApi =
      BoundedContextRules.dependOnEachOtherOnlyThroughApi("com.example.samples.s24");

  /**
   * The library's opt-in rule for where an integration-event subscriber lives.
   *
   * <p>Opted into because this sample has exactly one, and its placement is an argument the scenario makes: an event
   * arrives over a transport, so the class that receives it is an inbound adapter — even while the transport is the
   * in-process publisher and there is no wire at all.
   */
  @ArchTest
  static final ArchRule integrationEventSubscribersAreAdapters =
      EventRules.integrationEventListenersShouldResideInAdapter();

  /** The library's opt-in rule that a published fact lives in the published contract. */
  @ArchTest
  static final ArchRule integrationEventsArePublished = EventRules.integrationEventsShouldResideInApi();

  // ---------------------------------------------------------------------------------------------------
  // Four rules the library does not have, and each closes a hole the ones above leave open.
  // ---------------------------------------------------------------------------------------------------

  /**
   * <strong>An {@code api} package depends on nothing inside its own context.</strong>
   *
   * <p>The hole this closes: the library's rule makes everybody come in through {@code api}, and says nothing about what
   * {@code api} itself may reach for. A {@code CouponQuote} that carried a {@code Coupon} would satisfy the library
   * perfectly and would publish the model — the caller would be one field access away from the aggregate, and every
   * invariant in it would have become a promise.
   *
   * <p>This is the rule to copy first. It turns "keep the contract small" from an aspiration into a compile-time fact,
   * and it is the one that makes the {@code api} package reviewable: whatever is in there, it does not drag anything
   * behind it.
   */
  @ArchTest
  static final ArchRule theapiPackagesAreLeaves =
      noClasses()
          .that()
          .resideInAPackage("..api..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("..domain..", "..application..", "..infrastructure..", "..interfaces..")
          .as("a published contract must not depend on the model it is a contract for")
          .allowEmptyShould(true);

  /**
   * <strong>Published types are immutable</strong> — the guarantee the {@code @ValueObject} annotation would have given,
   * put back by hand.
   *
   * <p>The hole this closes is not a design hole, it is a collision between two of the library's own rules.
   * {@code BuildingBlockRules.domainBuildingBlocksShouldResideInDomain} — inside the parameterless {@code all()} —
   * requires every {@code @ValueObject} to live in {@code ..domain..}. {@code BoundedContextRules} requires anything
   * another context may touch to live in {@code ..api..}. A published identifier ({@code CouponCode}) and a shared-kernel
   * value ({@code Money}) are both value objects that must be in {@code api}, so they cannot carry the annotation — and
   * with it goes {@code valueObjectsShouldBeImmutable}, on precisely the types most exposed.
   *
   * <p>The library already makes the distinction this needs, one concept over: a domain event stays in {@code domain}
   * while an <em>integration</em> event legitimately lives in {@code api}. There is no such pair for value objects. Filed
   * as {@code issue-00170}; this rule is the local workaround, and it is weaker than the annotation because it has to be
   * spelled out per project.
   */
  @ArchTest
  static final ArchRule thepublishedTypesAreStillImmutable =
      com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes()
          .that()
          .resideInAPackage("..api..")
          .and()
          .areNotInterfaces()
          .and()
          .areNotEnums()
          .and()
          .areTopLevelClasses()
          .should()
          .haveOnlyFinalFields()
          .as("a published type is compared by its attributes, so it must not change after construction")
          .allowEmptyShould(true);

  /**
   * <strong>No {@code domain} package knows another context exists</strong> — not even through its published contract.
   *
   * <p>The hole this closes: {@code ordering.domain} → {@code coupons.api} passes the library's rule. It is going through
   * the front door, after all. And it is still the wrong dependency, for a reason that has nothing to do with
   * encapsulation: an aggregate that holds another context's port is an aggregate that can time out, retry, and fail
   * halfway. Cross-context collaboration belongs in the application layer because that is the only layer where a
   * transaction boundary and a failure policy exist to be reasoned about.
   *
   * <p>The cost is visible in {@code Order}: it stores its coupon code as a {@code String} rather than as the published
   * {@code CouponCode}, and loses that type's validation. The sample takes the trade and says so.
   */
  @ArchTest
  static final ArchRule nodomainKnowsAnotherContextExists =
      noClasses()
          .that()
          .resideInAPackage("com.example.samples.s24.ordering.domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("com.example.samples.s24.coupons..", "com.example.samples.s24.inventory..")
          .as("an aggregate that holds another context's port is an aggregate that can time out")
          .allowEmptyShould(true);

  /** The same rule from the new context's side, so it is symmetric rather than a check on the old code. */
  @ArchTest
  static final ArchRule thenewContextsDomainKnowsNobodyEither =
      noClasses()
          .that()
          .resideInAPackage("com.example.samples.s24.coupons.domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("com.example.samples.s24.ordering..", "com.example.samples.s24.inventory..")
          .as("the coupons model computes against an amount and has never heard of an order")
          .allowEmptyShould(true);

  /**
   * <strong>The contexts form no cycle.</strong>
   *
   * <p>The hole this closes, and it is the largest one: the library's rule permits ordering to depend on
   * {@code coupons.api} <em>and</em> coupons to depend on {@code ordering.api}. Both go through the front door. Nothing
   * complains, nothing breaks, and the two contexts are now inseparable — because the day somebody makes them two Maven
   * modules, Maven refuses a circular dependency and there is no incremental way out.
   *
   * <p>Which is why the asynchronous half of this sample's integration lives in {@code contextmap} instead of in coupons.
   * That placement is unusual and the analysis document measures what it buys: moving the subscriber into coupons — the
   * ordinary, defensible refactor — turns this rule red and leaves every library rule green.
   *
   * <p>{@code sharedkernel} is ignored because everything depends on it and it depends on nothing; including it would
   * report the fan-in that a shared kernel is for.
   */
  @ArchTest
  static final ArchRule thecontextsFormNoCycle =
      slices()
          .matching("com.example.samples.s24.(*)..")
          .namingSlices("context $1")
          .should()
          .beFreeOfCycles()
          .ignoreDependency(
              com.tngtech.archunit.base.DescribedPredicate.alwaysTrue(),
              com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage(
                  "com.example.samples.s24.sharedkernel.."))
          .as("two contexts that depend on each other cannot become two modules")
          .allowEmptyShould(true);

  /**
   * <strong>The shared kernel is a leaf.</strong>
   *
   * <p>The mechanical form of "what belongs in a shared kernel", and the only one that survives an argument. Taste says
   * {@code Money} yes and {@code OrderStatus} no; this rule says the same thing without needing anybody to agree: the
   * moment the shared kernel needs to know about a context, it is that context's type wearing a shared name.
   */
  @ArchTest
  static final ArchRule thesharedKernelIsALeaf =
      noClasses()
          .that()
          .resideInAPackage("com.example.samples.s24.sharedkernel..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "com.example.samples.s24.ordering..",
              "com.example.samples.s24.inventory..",
              "com.example.samples.s24.coupons..",
              "com.example.samples.s24.contextmap..")
          .as("a shared kernel that knows about a context is that context's type wearing a shared name")
          .allowEmptyShould(true);
}

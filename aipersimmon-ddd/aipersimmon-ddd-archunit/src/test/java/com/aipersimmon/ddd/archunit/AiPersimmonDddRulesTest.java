package com.aipersimmon.ddd.archunit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.archunit.fixture.bad.ordering.domain.BadDomainDependsOnCqrs;
import com.aipersimmon.ddd.archunit.fixture.bad.ordering.domain.BadValueObjectComparedByReference;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class AiPersimmonDddRulesTest {

  private static final JavaClasses GOOD =
      new ClassFileImporter().importPackages("com.aipersimmon.ddd.archunit.fixture.good");
  private static final JavaClasses EVENT_TYPE_FIXTURES =
      new ClassFileImporter().importPackages("com.aipersimmon.ddd.archunit.fixture.eventtype");
  private static final JavaClasses BAD =
      new ClassFileImporter().importPackages("com.aipersimmon.ddd.archunit.fixture.bad");
  private static final JavaClasses APIDOC_BAD =
      new ClassFileImporter().importPackages("com.aipersimmon.ddd.archunit.fixture.apidoc");

  /**
   * Isolated so the after-commit assertions can be exact: one misplaced, unmarked
   * {@code @TransactionalEventListener} and one plain method that merely takes a domain event.
   * Anything the event rules report over this package is either that one method or a false
   * positive.
   */
  private static final JavaClasses AFTER_COMMIT =
      new ClassFileImporter().importPackages("com.aipersimmon.ddd.archunit.fixture.aftercommit");

  private static final JavaClasses VALIDATION_GOOD =
      new ClassFileImporter()
          .importPackages("com.aipersimmon.ddd.archunit.fixture.validation.good");
  private static final JavaClasses VALIDATION_BAD =
      new ClassFileImporter().importPackages("com.aipersimmon.ddd.archunit.fixture.validation.bad");
  private static final String CONTEXTS_GOOD_BASE =
      "com.aipersimmon.ddd.archunit.fixture.contexts.good";
  private static final String CONTEXTS_BAD_BASE =
      "com.aipersimmon.ddd.archunit.fixture.contexts.bad";
  private static final JavaClasses CONTEXTS_GOOD =
      new ClassFileImporter().importPackages(CONTEXTS_GOOD_BASE);
  private static final JavaClasses CONTEXTS_BAD =
      new ClassFileImporter().importPackages(CONTEXTS_BAD_BASE);

  @Test
  void domainShouldNotDependOnOuterLayers_passesForGood() {
    assertDoesNotThrow(() -> LayeringRules.domainShouldNotDependOnOuterLayers().check(GOOD));
  }

  @Test
  void domainShouldNotDependOnOuterLayers_failsForBad() {
    assertThrows(
        AssertionError.class, () -> LayeringRules.domainShouldNotDependOnOuterLayers().check(BAD));
  }

  @Test
  void domainEventsShouldStayInDomain_passesForGood() {
    assertDoesNotThrow(() -> EventRules.domainEventsShouldStayInDomain().check(GOOD));
  }

  @Test
  void domainEventsShouldStayInDomain_failsForBad() {
    assertThrows(
        AssertionError.class, () -> EventRules.domainEventsShouldStayInDomain().check(BAD));
  }

  @Test
  void domainEventListenersShouldResideInApplicationOrDomain_passesForGood() {
    assertDoesNotThrow(
        () -> EventRules.domainEventListenersShouldResideInApplicationOrDomain().check(GOOD));
  }

  @Test
  void domainEventListenersShouldResideInApplicationOrDomain_failsForBad() {
    assertThrows(
        AssertionError.class,
        () -> EventRules.domainEventListenersShouldResideInApplicationOrDomain().check(BAD));
  }

  @Test
  void integrationEventListenersShouldResideInAdapter_passesForGood() {
    assertDoesNotThrow(
        () -> EventRules.integrationEventListenersShouldResideInAdapter().check(GOOD));
  }

  @Test
  void integrationEventListenersShouldResideInAdapter_failsForBad() {
    assertThrows(
        AssertionError.class,
        () -> EventRules.integrationEventListenersShouldResideInAdapter().check(BAD));
  }

  @Test
  void domainEventListenersShouldBeAnnotatedWithDomainEventHandler_passesForGood() {
    assertDoesNotThrow(
        () -> EventRules.domainEventListenersShouldBeAnnotatedWithDomainEventHandler().check(GOOD));
  }

  @Test
  void domainEventListenersShouldBeAnnotatedWithDomainEventHandler_failsForBad() {
    assertThrows(
        AssertionError.class,
        () -> EventRules.domainEventListenersShouldBeAnnotatedWithDomainEventHandler().check(BAD));
  }

  /**
   * The placement rule sees {@code @TransactionalEventListener}, which carries
   * {@code @EventListener} as a meta-annotation rather than directly. Measured over the isolated
   * fixture so the assertion is about this method and not about the other violations the {@code
   * bad} package is full of.
   */
  @Test
  void domainEventListenersShouldResideInApplicationOrDomain_seesAfterCommitSubscribers() {
    AssertionError error =
        assertThrows(
            AssertionError.class,
            () ->
                EventRules.domainEventListenersShouldResideInApplicationOrDomain()
                    .check(AFTER_COMMIT));
    assertTrue(
        error.getMessage().contains("AfterCommitSubscriberInAdapter"),
        () -> "the after-commit subscriber should be reported: " + error.getMessage());
  }

  /** Same for the marker rule — the second of the three rules built on that predicate. */
  @Test
  void domainEventListenersShouldBeAnnotatedWithDomainEventHandler_seesAfterCommitSubscribers() {
    AssertionError error =
        assertThrows(
            AssertionError.class,
            () ->
                EventRules.domainEventListenersShouldBeAnnotatedWithDomainEventHandler()
                    .check(AFTER_COMMIT));
    assertTrue(
        error.getMessage().contains("AfterCommitSubscriberInAdapter"),
        () -> "the unmarked after-commit subscriber should be reported: " + error.getMessage());
  }

  /**
   * And the control that keeps the widened predicate honest: a method that takes a domain event but
   * carries no subscription annotation, direct or meta, is not a subscriber. It sits in the same
   * misplaced package as the reported one, so if the predicate had degenerated into "takes a domain
   * event" it would appear in both reports.
   */
  @Test
  void aplainMethodTakingADomainEventIsNotTreatedAsASubscriber() {
    AssertionError placement =
        assertThrows(
            AssertionError.class,
            () ->
                EventRules.domainEventListenersShouldResideInApplicationOrDomain()
                    .check(AFTER_COMMIT));
    AssertionError marker =
        assertThrows(
            AssertionError.class,
            () ->
                EventRules.domainEventListenersShouldBeAnnotatedWithDomainEventHandler()
                    .check(AFTER_COMMIT));
    assertFalse(
        placement.getMessage().contains("NotASubscriberInAdapter"),
        () -> "plain method reported by the placement rule: " + placement.getMessage());
    assertFalse(
        marker.getMessage().contains("NotASubscriberInAdapter"),
        () -> "plain method reported by the marker rule: " + marker.getMessage());
  }

  /**
   * The third rule on that predicate, in the meta-annotated spelling: an integration-event
   * subscriber declared with {@code @TransactionalEventListener} outside an adapter is reported.
   */
  @Test
  void integrationEventListenersShouldResideInAdapter_seesAfterCommitSubscribers() {
    AssertionError error =
        assertThrows(
            AssertionError.class,
            () -> EventRules.integrationEventListenersShouldResideInAdapter().check(BAD));
    assertTrue(
        error.getMessage().contains("BadAfterCommitIntegrationEventListenerInApplication"),
        () -> "the after-commit integration subscriber should be reported: " + error.getMessage());
  }

  @Test
  void commandHandlersShouldNotDependOnOtherCommandHandlers_passesForGood() {
    assertDoesNotThrow(
        () -> CqrsRules.commandHandlersShouldNotDependOnOtherCommandHandlers().check(GOOD));
  }

  @Test
  void commandHandlersShouldNotDependOnOtherCommandHandlers_failsForBad() {
    assertThrows(
        AssertionError.class,
        () -> CqrsRules.commandHandlersShouldNotDependOnOtherCommandHandlers().check(BAD));
  }

  @Test
  void commandHandlersAndApplicationShouldNotCallSendAs_passesForGood() {
    assertDoesNotThrow(
        () -> CqrsRules.commandHandlersAndApplicationShouldNotCallSendAs().check(GOOD));
  }

  @Test
  void commandHandlersAndApplicationShouldNotCallSendAs_failsForBad() {
    assertThrows(
        AssertionError.class,
        () -> CqrsRules.commandHandlersAndApplicationShouldNotCallSendAs().check(BAD));
  }

  @Test
  void versionWitnessIsAdvancedOnlyByPersistenceAdapters_passesForGood() {
    assertDoesNotThrow(
        () -> BuildingBlockRules.versionWitnessIsAdvancedOnlyByPersistenceAdapters().check(GOOD));
  }

  @Test
  void versionWitnessIsAdvancedOnlyByPersistenceAdapters_failsForBad() {
    assertThrows(
        AssertionError.class,
        () -> BuildingBlockRules.versionWitnessIsAdvancedOnlyByPersistenceAdapters().check(BAD));
  }

  @Test
  void commandComponentsShouldDeclareValidationConstraints_passesForGood() {
    assertDoesNotThrow(
        () ->
            CqrsRules.commandComponentsShouldDeclareValidationConstraints().check(VALIDATION_GOOD));
  }

  @Test
  void commandComponentsShouldDeclareValidationConstraints_failsForBad() {
    assertThrows(
        AssertionError.class,
        () ->
            CqrsRules.commandComponentsShouldDeclareValidationConstraints().check(VALIDATION_BAD));
  }

  @Test
  void commandAndQueryHandlersShouldResideInApplication_passesForGood() {
    assertDoesNotThrow(
        () -> CqrsRules.commandAndQueryHandlersShouldResideInApplication().check(GOOD));
  }

  @Test
  void commandAndQueryHandlersShouldResideInApplication_failsForBad() {
    assertThrows(
        AssertionError.class,
        () -> CqrsRules.commandAndQueryHandlersShouldResideInApplication().check(BAD));
  }

  @Test
  void domainShouldBeFrameworkFree_passesForGood() {
    assertDoesNotThrow(() -> LayeringRules.domainShouldBeFrameworkFree().check(GOOD));
  }

  @Test
  void domainShouldNotDependOnApiDocumentation_passesForGood() {
    assertDoesNotThrow(() -> LayeringRules.domainShouldNotDependOnApiDocumentation().check(GOOD));
  }

  @Test
  void domainShouldNotDependOnApiDocumentation_failsForBad() {
    assertThrows(
        AssertionError.class,
        () -> LayeringRules.domainShouldNotDependOnApiDocumentation().check(APIDOC_BAD));
  }

  @Test
  void adapterShouldNotDependOnDomain_passesForGood() {
    assertDoesNotThrow(() -> LayeringRules.adapterShouldNotDependOnDomain().check(GOOD));
  }

  @Test
  void adapterShouldNotDependOnDomain_failsForBad() {
    assertThrows(
        AssertionError.class, () -> LayeringRules.adapterShouldNotDependOnDomain().check(BAD));
  }

  @Test
  void invariantsShouldResideInDomain_passesForGood() {
    assertDoesNotThrow(() -> InvariantAndErrorRules.invariantsShouldResideInDomain().check(GOOD));
  }

  @Test
  void invariantsShouldResideInDomain_failsForBad() {
    assertThrows(
        AssertionError.class,
        () -> InvariantAndErrorRules.invariantsShouldResideInDomain().check(BAD));
  }

  @Test
  void invariantViolationsShouldOnlyComeFromCheckInvariant_passesForGood() {
    assertDoesNotThrow(
        () ->
            InvariantAndErrorRules.invariantViolationsShouldOnlyComeFromCheckInvariant()
                .check(GOOD));
  }

  @Test
  void invariantViolationsShouldOnlyComeFromCheckInvariant_failsForBad() {
    assertThrows(
        AssertionError.class,
        () ->
            InvariantAndErrorRules.invariantViolationsShouldOnlyComeFromCheckInvariant()
                .check(BAD));
  }

  @Test
  void invariantsShouldNotBeSpringComponents_passesForGood() {
    assertDoesNotThrow(
        () -> InvariantAndErrorRules.invariantsShouldNotBeSpringComponents().check(GOOD));
  }

  @Test
  void invariantsShouldNotBeSpringComponents_failsForBad() {
    assertThrows(
        AssertionError.class,
        () -> InvariantAndErrorRules.invariantsShouldNotBeSpringComponents().check(BAD));
  }

  @Test
  void domainBuildingBlocksShouldResideInDomain_passesForGood() {
    assertDoesNotThrow(
        () -> BuildingBlockRules.domainBuildingBlocksShouldResideInDomain().check(GOOD));
  }

  @Test
  void domainBuildingBlocksShouldResideInDomain_failsForBad() {
    assertThrows(
        AssertionError.class,
        () -> BuildingBlockRules.domainBuildingBlocksShouldResideInDomain().check(BAD));
  }

  /**
   * A published value object keeps its marker: {@code ..api..} is a legal home for
   * {@code @ValueObject} so that a multi-context layout does not have to choose between this rule
   * and {@link BoundedContextRules#dependOnEachOtherOnlyThroughApi(String)}. The {@code good}
   * fixture has one in {@code ordering.api}, and it must not be reported.
   */
  @Test
  void apublishedValueObjectMayResideInApi() {
    assertDoesNotThrow(
        () -> BuildingBlockRules.valueObjectsShouldResideInDomainOrApi().check(GOOD));
    assertDoesNotThrow(
        () -> BuildingBlockRules.domainBuildingBlocksShouldResideInDomain().check(GOOD));
  }

  /**
   * The allowance is for value objects only. An {@code @Entity} in {@code ..api..} is still a
   * violation — asserted by name, because the point of this test is that the relaxation did not
   * spread to the annotations that must not be published.
   */
  @Test
  void anentityInApiIsStillReported() {
    AssertionError error =
        assertThrows(
            AssertionError.class,
            () -> BuildingBlockRules.aggregatesAndEntitiesShouldResideInDomain().check(BAD));
    assertTrue(
        error.getMessage().contains("BadEntityInApi"),
        () -> "an @Entity in ..api.. should be reported: " + error.getMessage());
  }

  /**
   * And the check the allowance exists to restore: because a published value object may keep its
   * marker, {@code valueObjectsShouldBeImmutable} still covers it. A mutable one in {@code ..api..}
   * is reported by name.
   */
  @Test
  void amutablePublishedValueObjectIsStillReported() {
    AssertionError error =
        assertThrows(
            AssertionError.class,
            () -> BuildingBlockRules.valueObjectsShouldBeImmutable().check(BAD));
    assertTrue(
        error.getMessage().contains("BadMutablePublishedValueObject"),
        () -> "a mutable published value object should be reported: " + error.getMessage());
  }

  /**
   * A {@code @ValueObject} in the application layer is neither domain nor api, so it is still
   * reported. Names the fixture, because a rule that had been widened to {@code resideInAnyPackage}
   * with a pattern too loose would let this through silently.
   */
  @Test
  void avalueObjectOutsideDomainAndApiIsStillReported() {
    AssertionError error =
        assertThrows(
            AssertionError.class,
            () -> BuildingBlockRules.valueObjectsShouldResideInDomainOrApi().check(BAD));
    assertTrue(
        error.getMessage().contains("BadValueObjectInApplication"),
        () -> "a @ValueObject in the application layer should be reported: " + error.getMessage());
  }

  @Test
  void domainServicesShouldResideInDomain_passesForGood() {
    assertDoesNotThrow(() -> BuildingBlockRules.domainServicesShouldResideInDomain().check(GOOD));
  }

  @Test
  void domainServicesShouldResideInDomain_failsForBad() {
    assertThrows(
        AssertionError.class,
        () -> BuildingBlockRules.domainServicesShouldResideInDomain().check(BAD));
  }

  @Test
  void aggregateRootsShouldExtendAbstractAggregateRoot_passesForGood() {
    assertDoesNotThrow(
        () -> BuildingBlockRules.aggregateRootsShouldExtendAbstractAggregateRoot().check(GOOD));
  }

  @Test
  void aggregateRootsShouldExtendAbstractAggregateRoot_failsForBad() {
    assertThrows(
        AssertionError.class,
        () -> BuildingBlockRules.aggregateRootsShouldExtendAbstractAggregateRoot().check(BAD));
  }

  @Test
  void valueObjectsShouldBeImmutable_passesForGood() {
    assertDoesNotThrow(() -> BuildingBlockRules.valueObjectsShouldBeImmutable().check(GOOD));
  }

  @Test
  void valueObjectsShouldBeImmutable_failsForBad() {
    assertThrows(
        AssertionError.class, () -> BuildingBlockRules.valueObjectsShouldBeImmutable().check(BAD));
  }

  @Test
  void illegalStateTransitionsShouldOnlyComeFromTransitions_passesForGood() {
    assertDoesNotThrow(
        () ->
            InvariantAndErrorRules.illegalStateTransitionsShouldOnlyComeFromTransitions()
                .check(GOOD));
  }

  @Test
  void illegalStateTransitionsShouldOnlyComeFromTransitions_failsForBad() {
    assertThrows(
        AssertionError.class,
        () ->
            InvariantAndErrorRules.illegalStateTransitionsShouldOnlyComeFromTransitions()
                .check(BAD));
  }

  @Test
  void errorCodesShouldBeEnums_passesForGood() {
    assertDoesNotThrow(() -> InvariantAndErrorRules.errorCodesShouldBeEnums().check(GOOD));
  }

  @Test
  void errorCodesShouldBeEnums_failsForBad() {
    assertThrows(
        AssertionError.class, () -> InvariantAndErrorRules.errorCodesShouldBeEnums().check(BAD));
  }

  @Test
  void portsShouldBeInterfacesInDomain_passesForGood() {
    assertDoesNotThrow(() -> RepositoryRules.portsShouldBeInterfacesInDomain().check(GOOD));
  }

  @Test
  void portsShouldBeInterfacesInDomain_failsForBad() {
    assertThrows(
        AssertionError.class, () -> RepositoryRules.portsShouldBeInterfacesInDomain().check(BAD));
  }

  @Test
  void implementationsShouldResideInInfrastructure_passesForGood() {
    assertDoesNotThrow(
        () -> RepositoryRules.implementationsShouldResideInInfrastructure().check(GOOD));
  }

  @Test
  void implementationsShouldResideInInfrastructure_failsForBad() {
    assertThrows(
        AssertionError.class,
        () -> RepositoryRules.implementationsShouldResideInInfrastructure().check(BAD));
  }

  @Test
  void implementationsShouldBeSpringRepositories_passesForGood() {
    assertDoesNotThrow(
        () -> RepositoryRules.implementationsShouldBeSpringRepositories().check(GOOD));
  }

  @Test
  void implementationsShouldBeSpringRepositories_failsForBad() {
    assertThrows(
        AssertionError.class,
        () -> RepositoryRules.implementationsShouldBeSpringRepositories().check(BAD));
  }

  @Test
  void integrationEventsShouldResideInApi_passesForGood() {
    assertDoesNotThrow(() -> EventRules.integrationEventsShouldResideInApi().check(GOOD));
  }

  @Test
  void integrationEventsShouldResideInApi_failsForBad() {
    assertThrows(
        AssertionError.class, () -> EventRules.integrationEventsShouldResideInApi().check(BAD));
  }

  @Test
  void integrationEventsShouldDeclareEventType_passesForGood() {
    assertDoesNotThrow(() -> EventRules.integrationEventsShouldDeclareEventType().check(GOOD));
  }

  @Test
  void integrationEventsShouldDeclareEventType_failsForBad() {
    assertThrows(
        AssertionError.class,
        () -> EventRules.integrationEventsShouldDeclareEventType().check(BAD));
  }

  /**
   * Characterizes every violation branch of the rule's condition in one check: a missing
   * {@code @EventType}, a blank name, a version below 1, and a shared {@code (name, version)} (the
   * last also exercises the {@code init} collision index). The valid control in the same fixture
   * package must not add noise — only the four expected kinds are reported.
   */
  @Test
  void integrationEventsShouldDeclareEventType_reportsEachViolationKind() {
    AssertionError error =
        assertThrows(
            AssertionError.class,
            () -> EventRules.integrationEventsShouldDeclareEventType().check(EVENT_TYPE_FIXTURES));
    String message = error.getMessage();
    assertTrue(
        message.contains("is not annotated with @EventType"),
        () -> "missing @EventType: " + message);
    assertTrue(
        message.contains("declares a blank @EventType name"), () -> "blank name: " + message);
    assertTrue(message.contains("which must be >= 1"), () -> "version < 1: " + message);
    assertTrue(message.contains("shares @EventType"), () -> "collision: " + message);
  }

  @Test
  void dependOnEachOtherOnlyThroughApi_passesForGood() {
    assertDoesNotThrow(
        () ->
            BoundedContextRules.dependOnEachOtherOnlyThroughApi(CONTEXTS_GOOD_BASE)
                .check(CONTEXTS_GOOD));
  }

  @Test
  void dependOnEachOtherOnlyThroughApi_failsForBad() {
    assertThrows(
        AssertionError.class,
        () ->
            BoundedContextRules.dependOnEachOtherOnlyThroughApi(CONTEXTS_BAD_BASE)
                .check(CONTEXTS_BAD));
  }

  @Test
  void domainShouldNotDependOnOperationLog_passesForGood() {
    assertDoesNotThrow(() -> OperationLogRules.domainShouldNotDependOnOperationLog().check(GOOD));
  }

  @Test
  void domainShouldNotDependOnOperationLog_failsForBad() {
    assertThrows(
        AssertionError.class,
        () -> OperationLogRules.domainShouldNotDependOnOperationLog().check(BAD));
  }

  @Test
  void operationLogShouldOnlyAnnotateApplicationCommands_passesForGood() {
    assertDoesNotThrow(
        () -> OperationLogRules.operationLogShouldOnlyAnnotateApplicationCommands().check(GOOD));
  }

  @Test
  void operationLogShouldOnlyAnnotateApplicationCommands_failsForBad() {
    assertThrows(
        AssertionError.class,
        () -> OperationLogRules.operationLogShouldOnlyAnnotateApplicationCommands().check(BAD));
  }

  @Test
  void domainShouldDependOnTheFrameworkCoreOnly_passesForGood() {
    assertDoesNotThrow(() -> LayeringRules.domainShouldDependOnTheFrameworkCoreOnly().check(GOOD));
  }

  /**
   * Names the fixture, because the point of the rule is the case {@code
   * domainShouldBeFrameworkFree} cannot see: a domain class holding {@code CommandContext} touches
   * no Spring, no JPA and no Jackson, so only this rule reports it.
   */
  @Test
  void domainShouldDependOnTheFrameworkCoreOnly_reportsAFrameworkModuleBeyondCore() {
    AssertionError error =
        assertThrows(
            AssertionError.class,
            () -> LayeringRules.domainShouldDependOnTheFrameworkCoreOnly().check(BAD));
    assertTrue(
        error.getMessage().contains("BadDomainDependsOnCqrs"),
        () ->
            "a domain class depending on the CQRS module should be reported: "
                + error.getMessage());
    assertDoesNotThrow(
        () ->
            LayeringRules.domainShouldBeFrameworkFree()
                .check(new ClassFileImporter().importClasses(BadDomainDependsOnCqrs.class)));
  }

  @Test
  void domainShouldNotUseAmbientTimeOrRandomness_passesForGood() {
    assertDoesNotThrow(
        () -> DeterminismRules.domainShouldNotUseAmbientTimeOrRandomness().check(GOOD));
  }

  @Test
  void domainShouldNotUseAmbientTimeOrRandomness_failsForBad() {
    AssertionError error =
        assertThrows(
            AssertionError.class,
            () -> DeterminismRules.domainShouldNotUseAmbientTimeOrRandomness().check(BAD));
    assertTrue(
        error.getMessage().contains("BadSelfStampingOrder"),
        () -> "the self-stamping aggregate should be reported: " + error.getMessage());
  }

  /**
   * The control that keeps the ambient check from degenerating into "touches {@code java.time}" or
   * "constructs a {@code Date}": a class in the same reported package whose every call takes its
   * value from an argument — {@code Instant.now(clock)}, {@code new Date(millis)}, {@code new
   * Random(seed)} — must not appear in the report.
   */
  @Test
  void avalueTakenFromAnArgumentIsNotAmbient() {
    AssertionError error =
        assertThrows(
            AssertionError.class,
            () -> DeterminismRules.domainShouldNotUseAmbientTimeOrRandomness().check(BAD));
    assertFalse(
        error.getMessage().contains("BadInjectedTimeOrder"),
        () -> "injected time and seeded randomness were reported: " + error.getMessage());
  }

  @Test
  void applicationShouldNotUseAmbientTimeOrRandomness_passesForGood() {
    assertDoesNotThrow(
        () -> DeterminismRules.applicationShouldNotUseAmbientTimeOrRandomness().check(GOOD));
  }

  @Test
  void valueObjectsShouldDeclareValueEquality_passesForGood() {
    assertDoesNotThrow(
        () -> BuildingBlockRules.valueObjectsShouldDeclareValueEquality().check(GOOD));
  }

  /**
   * The immutable-but-reference-compared value object is reported, and the immutability rule it
   * satisfies is asserted alongside — which is the whole reason the new rule exists rather than
   * being folded into the old one.
   */
  @Test
  void valueObjectsShouldDeclareValueEquality_reportsAnImmutableTypeWithoutEquals() {
    AssertionError error =
        assertThrows(
            AssertionError.class,
            () -> BuildingBlockRules.valueObjectsShouldDeclareValueEquality().check(BAD));
    assertTrue(
        error.getMessage().contains("BadValueObjectComparedByReference"),
        () -> "a value object without equals should be reported: " + error.getMessage());
    assertDoesNotThrow(
        () ->
            BuildingBlockRules.valueObjectsShouldBeImmutable()
                .check(
                    new ClassFileImporter()
                        .importClasses(BadValueObjectComparedByReference.class)));
  }

  @Test
  void aggregatesShouldReferenceOtherAggregatesByIdentity_passesForGood() {
    assertDoesNotThrow(
        () -> BuildingBlockRules.aggregatesShouldReferenceOtherAggregatesByIdentity().check(GOOD));
  }

  /**
   * Both spellings are reported — the held root and the collection of roots — and the fixture's
   * identifier field and its own {@code @Entity} members are not, which is what keeps the rule from
   * collapsing into "an aggregate holds no model type".
   */
  @Test
  void aggregatesShouldReferenceOtherAggregatesByIdentity_reportsHeldRootsInEveryShape() {
    AssertionError error =
        assertThrows(
            AssertionError.class,
            () ->
                BuildingBlockRules.aggregatesShouldReferenceOtherAggregatesByIdentity().check(BAD));
    assertTrue(
        error.getMessage().contains("reservedItem"),
        () -> "the held aggregate root should be reported: " + error.getMessage());
    assertTrue(
        error.getMessage().contains("relatedItems"),
        () -> "the collection of roots should be reported: " + error.getMessage());
  }

  @Test
  void readModelsShouldBeProjectionShapes_passesForGood() {
    assertDoesNotThrow(() -> CqrsRules.readModelsShouldBeProjectionShapes().check(GOOD));
  }

  @Test
  void readModelsShouldNotHoldAggregatesOrEntities_failsForBad() {
    AssertionError error =
        assertThrows(
            AssertionError.class,
            () -> CqrsRules.readModelsShouldNotHoldAggregatesOrEntities().check(BAD));
    assertTrue(
        error.getMessage().contains("BadOrderViewHoldingAggregate"),
        () -> "a read model holding an aggregate should be reported: " + error.getMessage());
  }

  @Test
  void readModelsShouldResideInApplicationOrApi_failsForBad() {
    AssertionError error =
        assertThrows(
            AssertionError.class,
            () -> CqrsRules.readModelsShouldResideInApplicationOrApi().check(BAD));
    assertTrue(
        error.getMessage().contains("BadReadModelInDomain"),
        () -> "a read model in the domain layer should be reported: " + error.getMessage());
  }

  @Test
  void queryResultsShouldNotBeAggregatesOrEntities_passesForGood() {
    assertDoesNotThrow(() -> CqrsRules.queryResultsShouldNotBeAggregatesOrEntities().check(GOOD));
  }

  /**
   * The aggregate sits inside an {@code Optional}, not at the top of the result type, so this also
   * measures that the rule reads the whole generic signature rather than only the erasure.
   */
  @Test
  void queryResultsShouldNotBeAggregatesOrEntities_seesThroughAGenericWrapper() {
    AssertionError error =
        assertThrows(
            AssertionError.class,
            () -> CqrsRules.queryResultsShouldNotBeAggregatesOrEntities().check(BAD));
    assertTrue(
        error.getMessage().contains("BadFindOrderAggregate"),
        () -> "a query answering with an aggregate should be reported: " + error.getMessage());
  }

  @Test
  void processDefinitionsShouldBePure_passesForGood() {
    assertDoesNotThrow(() -> ProcessRules.processDefinitionsShouldBePure().check(GOOD));
  }

  @Test
  void processDefinitionsShouldNotPerformIo_failsForBad() {
    AssertionError error =
        assertThrows(
            AssertionError.class,
            () -> ProcessRules.processDefinitionsShouldNotPerformIo().check(BAD));
    assertTrue(
        error.getMessage().contains("CommandBus"),
        () -> "a definition holding the command bus should be reported: " + error.getMessage());
  }

  @Test
  void processDefinitionsShouldBeDeterministic_failsForBad() {
    AssertionError error =
        assertThrows(
            AssertionError.class,
            () -> ProcessRules.processDefinitionsShouldBeDeterministic().check(BAD));
    assertTrue(
        error.getMessage().contains("BadImpureFulfilmentDefinition"),
        () -> "a definition branching on the clock should be reported: " + error.getMessage());
  }

  /**
   * The control for the purity rule: the compliant definition returns a {@code DispatchCommand}
   * naming an application command, which is a dependency on the application layer and must stay
   * allowed — a definition has to say what it wants dispatched.
   */
  @Test
  void apureDefinitionMayNameTheCommandItDispatches() {
    assertDoesNotThrow(() -> ProcessRules.processDefinitionsShouldNotPerformIo().check(GOOD));
  }

  @Test
  void externalizedShouldOnlyAnnotateIntegrationEvents_passesForGood() {
    assertDoesNotThrow(
        () -> EventRules.externalizedShouldOnlyAnnotateIntegrationEvents().check(GOOD));
  }

  /**
   * Both violation branches: the annotation on a non-event, and a real event with no destination.
   */
  @Test
  void externalizedShouldOnlyAnnotateIntegrationEvents_reportsBothViolationKinds() {
    AssertionError error =
        assertThrows(
            AssertionError.class,
            () -> EventRules.externalizedShouldOnlyAnnotateIntegrationEvents().check(BAD));
    assertTrue(
        error.getMessage().contains("BadExternalizedCommand"),
        () -> "@Externalized on a non-event should be reported: " + error.getMessage());
    assertTrue(
        error.getMessage().contains("names no target"),
        () -> "a blank target should be reported: " + error.getMessage());
  }

  @Test
  void commandsAndQueriesShouldResideInApplication_passesForGood() {
    assertDoesNotThrow(() -> CqrsRules.commandsAndQueriesShouldResideInApplication().check(GOOD));
  }

  @Test
  void commandsAndQueriesShouldResideInApplication_failsForBad() {
    AssertionError error =
        assertThrows(
            AssertionError.class,
            () -> CqrsRules.commandsAndQueriesShouldResideInApplication().check(BAD));
    assertTrue(
        error.getMessage().contains("BadCommandInDomain"),
        () -> "a command declared in the domain should be reported: " + error.getMessage());
  }

  @Test
  void dependenciesShouldBeConstructorInjected_passesForGood() {
    assertDoesNotThrow(() -> InjectionRules.dependenciesShouldBeConstructorInjected().check(GOOD));
  }

  /**
   * Both field spellings are reported — the {@code @Autowired} collaborator and the {@code @Value}
   * configuration — while the {@code @Value} on a <em>constructor parameter</em> in the good
   * fixtures' process definition is not, which is what keeps the rule off the sanctioned form.
   */
  @Test
  void dependenciesShouldBeConstructorInjected_reportsFieldsButNotConstructorParameters() {
    AssertionError error =
        assertThrows(
            AssertionError.class,
            () -> InjectionRules.dependenciesShouldBeConstructorInjected().check(BAD));
    assertTrue(
        error.getMessage().contains("placeOrder"),
        () -> "an @Autowired field should be reported: " + error.getMessage());
    assertTrue(
        error.getMessage().contains("limit"),
        () -> "a @Value field should be reported: " + error.getMessage());
    assertDoesNotThrow(() -> InjectionRules.dependenciesShouldBeConstructorInjected().check(GOOD));
  }

  @Test
  void persistenceMappingsShouldStayInInfrastructure_passesForGood() {
    assertDoesNotThrow(
        () -> PersistenceRules.persistenceMappingsShouldStayInInfrastructure().check(GOOD));
  }

  @Test
  void nothingOutsideInfrastructureShouldDependOnMappersOrRows_failsForBad() {
    AssertionError error =
        assertThrows(
            AssertionError.class,
            () ->
                PersistenceRules.nothingOutsideInfrastructureShouldDependOnMappersOrRows()
                    .check(BAD));
    assertTrue(
        error.getMessage().contains("BadRowReadingService"),
        () -> "an application class holding a mapper should be reported: " + error.getMessage());
  }

  @Test
  void portsShouldNotBeUsedByInboundAdapters_passesForGood() {
    assertDoesNotThrow(() -> RepositoryRules.portsShouldNotBeUsedByInboundAdapters().check(GOOD));
  }

  /**
   * The violating fixture sits in {@code ..interfaces..}, not {@code ..adapter..}, so this also
   * measures that the rule reads both accepted spellings of the interface layer.
   */
  @Test
  void portsShouldNotBeUsedByInboundAdapters_seesTheOtherSpellingOfTheInterfaceLayer() {
    AssertionError error =
        assertThrows(
            AssertionError.class,
            () -> RepositoryRules.portsShouldNotBeUsedByInboundAdapters().check(BAD));
    assertTrue(
        error.getMessage().contains("BadOrderResource"),
        () ->
            "an endpoint in ..interfaces.. holding a repository port should be reported: "
                + error.getMessage());
  }

  @Test
  void controllerSignaturesShouldNotExposeTheDomain_passesForGood() {
    assertDoesNotThrow(() -> WebRules.controllerSignaturesShouldNotExposeTheDomain().check(GOOD));
  }

  @Test
  void controllerSignaturesShouldNotExposeTheDomain_failsForBad() {
    AssertionError error =
        assertThrows(
            AssertionError.class,
            () -> WebRules.controllerSignaturesShouldNotExposeTheDomain().check(BAD));
    assertTrue(
        error.getMessage().contains("BadOrderResource"),
        () -> "an endpoint answering with an aggregate should be reported: " + error.getMessage());
  }

  @Test
  void all_passesForGood() {
    assertDoesNotThrow(() -> AiPersimmonDddRules.all().check(GOOD));
  }
}

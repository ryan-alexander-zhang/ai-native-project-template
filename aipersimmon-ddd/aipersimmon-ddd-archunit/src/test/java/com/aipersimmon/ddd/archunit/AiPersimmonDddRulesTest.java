package com.aipersimmon.ddd.archunit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
  void all_passesForGood() {
    assertDoesNotThrow(() -> AiPersimmonDddRules.all().check(GOOD));
  }
}

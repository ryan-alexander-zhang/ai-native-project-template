package com.aipersimmon.ddd.archunit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class AiPersimmonDddRulesTest {

  private static final JavaClasses GOOD =
      new ClassFileImporter().importPackages("com.aipersimmon.ddd.archunit.fixture.good");
  private static final JavaClasses BAD =
      new ClassFileImporter().importPackages("com.aipersimmon.ddd.archunit.fixture.bad");
  private static final JavaClasses ANNOTATED_EVENT_IN_ADAPTER =
      new ClassFileImporter().importPackages("com.aipersimmon.ddd.archunit.fixture.annotated");
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
  void domainEventsShouldStayInDomain_catchesAnnotatedEventOutsideDomain() {
    assertThrows(
        AssertionError.class,
        () -> EventRules.domainEventsShouldStayInDomain().check(ANNOTATED_EVENT_IN_ADAPTER));
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
  void useCasesShouldResideInApplication_passesForGood() {
    assertDoesNotThrow(() -> CqrsRules.useCasesShouldResideInApplication().check(GOOD));
  }

  @Test
  void useCasesShouldResideInApplication_failsForBad() {
    assertThrows(
        AssertionError.class, () -> CqrsRules.useCasesShouldResideInApplication().check(BAD));
  }

  @Test
  void domainShouldBeFrameworkFree_passesForGood() {
    assertDoesNotThrow(() -> LayeringRules.domainShouldBeFrameworkFree().check(GOOD));
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
  void repositoryPortsShouldBeInterfacesInDomain_passesForGood() {
    assertDoesNotThrow(
        () -> RepositoryRules.repositoryPortsShouldBeInterfacesInDomain().check(GOOD));
  }

  @Test
  void repositoryPortsShouldBeInterfacesInDomain_failsForBad() {
    assertThrows(
        AssertionError.class,
        () -> RepositoryRules.repositoryPortsShouldBeInterfacesInDomain().check(BAD));
  }

  @Test
  void repositoryImplementationsShouldResideInInfrastructure_passesForGood() {
    assertDoesNotThrow(
        () -> RepositoryRules.repositoryImplementationsShouldResideInInfrastructure().check(GOOD));
  }

  @Test
  void repositoryImplementationsShouldResideInInfrastructure_failsForBad() {
    assertThrows(
        AssertionError.class,
        () -> RepositoryRules.repositoryImplementationsShouldResideInInfrastructure().check(BAD));
  }

  @Test
  void repositoryImplementationsShouldBeSpringRepositories_passesForGood() {
    assertDoesNotThrow(
        () -> RepositoryRules.repositoryImplementationsShouldBeSpringRepositories().check(GOOD));
  }

  @Test
  void repositoryImplementationsShouldBeSpringRepositories_failsForBad() {
    assertThrows(
        AssertionError.class,
        () -> RepositoryRules.repositoryImplementationsShouldBeSpringRepositories().check(BAD));
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

  @Test
  void boundedContextsShouldOnlyDependOnEachOthersApi_passesForGood() {
    assertDoesNotThrow(
        () ->
            BoundedContextRules.boundedContextsShouldOnlyDependOnEachOthersApi(CONTEXTS_GOOD_BASE)
                .check(CONTEXTS_GOOD));
  }

  @Test
  void boundedContextsShouldOnlyDependOnEachOthersApi_failsForBad() {
    assertThrows(
        AssertionError.class,
        () ->
            BoundedContextRules.boundedContextsShouldOnlyDependOnEachOthersApi(CONTEXTS_BAD_BASE)
                .check(CONTEXTS_BAD));
  }

  @Test
  void all_passesForGood() {
    assertDoesNotThrow(() -> AiPersimmonDddRules.all().check(GOOD));
  }
}

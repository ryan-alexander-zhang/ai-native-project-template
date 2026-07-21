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
    assertDoesNotThrow(() -> AiPersimmonDddRules.domainShouldNotDependOnOuterLayers().check(GOOD));
  }

  @Test
  void domainShouldNotDependOnOuterLayers_failsForBad() {
    assertThrows(
        AssertionError.class,
        () -> AiPersimmonDddRules.domainShouldNotDependOnOuterLayers().check(BAD));
  }

  @Test
  void domainEventsShouldStayInDomain_passesForGood() {
    assertDoesNotThrow(() -> AiPersimmonDddRules.domainEventsShouldStayInDomain().check(GOOD));
  }

  @Test
  void domainEventsShouldStayInDomain_failsForBad() {
    assertThrows(
        AssertionError.class,
        () -> AiPersimmonDddRules.domainEventsShouldStayInDomain().check(BAD));
  }

  @Test
  void domainEventsShouldStayInDomain_catchesAnnotatedEventOutsideDomain() {
    assertThrows(
        AssertionError.class,
        () ->
            AiPersimmonDddRules.domainEventsShouldStayInDomain().check(ANNOTATED_EVENT_IN_ADAPTER));
  }

  @Test
  void domainEventListenersShouldResideInApplicationOrDomain_passesForGood() {
    assertDoesNotThrow(
        () ->
            AiPersimmonDddRules.domainEventListenersShouldResideInApplicationOrDomain()
                .check(GOOD));
  }

  @Test
  void domainEventListenersShouldResideInApplicationOrDomain_failsForBad() {
    assertThrows(
        AssertionError.class,
        () ->
            AiPersimmonDddRules.domainEventListenersShouldResideInApplicationOrDomain().check(BAD));
  }

  @Test
  void integrationEventListenersShouldResideInAdapter_passesForGood() {
    assertDoesNotThrow(
        () -> AiPersimmonDddRules.integrationEventListenersShouldResideInAdapter().check(GOOD));
  }

  @Test
  void integrationEventListenersShouldResideInAdapter_failsForBad() {
    assertThrows(
        AssertionError.class,
        () -> AiPersimmonDddRules.integrationEventListenersShouldResideInAdapter().check(BAD));
  }

  @Test
  void domainEventListenersShouldBeAnnotatedWithDomainEventHandler_passesForGood() {
    assertDoesNotThrow(
        () ->
            AiPersimmonDddRules.domainEventListenersShouldBeAnnotatedWithDomainEventHandler()
                .check(GOOD));
  }

  @Test
  void domainEventListenersShouldBeAnnotatedWithDomainEventHandler_failsForBad() {
    assertThrows(
        AssertionError.class,
        () ->
            AiPersimmonDddRules.domainEventListenersShouldBeAnnotatedWithDomainEventHandler()
                .check(BAD));
  }

  @Test
  void commandHandlersShouldNotDependOnOtherCommandHandlers_passesForGood() {
    assertDoesNotThrow(
        () ->
            AiPersimmonDddRules.commandHandlersShouldNotDependOnOtherCommandHandlers().check(GOOD));
  }

  @Test
  void commandHandlersShouldNotDependOnOtherCommandHandlers_failsForBad() {
    assertThrows(
        AssertionError.class,
        () ->
            AiPersimmonDddRules.commandHandlersShouldNotDependOnOtherCommandHandlers().check(BAD));
  }

  @Test
  void commandHandlersAndApplicationShouldNotCallSendAs_passesForGood() {
    assertDoesNotThrow(
        () -> AiPersimmonDddRules.commandHandlersAndApplicationShouldNotCallSendAs().check(GOOD));
  }

  @Test
  void commandHandlersAndApplicationShouldNotCallSendAs_failsForBad() {
    assertThrows(
        AssertionError.class,
        () -> AiPersimmonDddRules.commandHandlersAndApplicationShouldNotCallSendAs().check(BAD));
  }

  @Test
  void domainShouldBeFrameworkFree_passesForGood() {
    assertDoesNotThrow(() -> AiPersimmonDddRules.domainShouldBeFrameworkFree().check(GOOD));
  }

  @Test
  void adapterShouldNotDependOnDomain_passesForGood() {
    assertDoesNotThrow(() -> AiPersimmonDddRules.adapterShouldNotDependOnDomain().check(GOOD));
  }

  @Test
  void adapterShouldNotDependOnDomain_failsForBad() {
    assertThrows(
        AssertionError.class,
        () -> AiPersimmonDddRules.adapterShouldNotDependOnDomain().check(BAD));
  }

  @Test
  void invariantsShouldResideInDomain_passesForGood() {
    assertDoesNotThrow(() -> AiPersimmonDddRules.invariantsShouldResideInDomain().check(GOOD));
  }

  @Test
  void invariantsShouldResideInDomain_failsForBad() {
    assertThrows(
        AssertionError.class,
        () -> AiPersimmonDddRules.invariantsShouldResideInDomain().check(BAD));
  }

  @Test
  void invariantViolationsShouldOnlyComeFromCheckInvariant_passesForGood() {
    assertDoesNotThrow(
        () ->
            AiPersimmonDddRules.invariantViolationsShouldOnlyComeFromCheckInvariant().check(GOOD));
  }

  @Test
  void invariantViolationsShouldOnlyComeFromCheckInvariant_failsForBad() {
    assertThrows(
        AssertionError.class,
        () -> AiPersimmonDddRules.invariantViolationsShouldOnlyComeFromCheckInvariant().check(BAD));
  }

  @Test
  void invariantsShouldNotBeSpringComponents_passesForGood() {
    assertDoesNotThrow(
        () -> AiPersimmonDddRules.invariantsShouldNotBeSpringComponents().check(GOOD));
  }

  @Test
  void invariantsShouldNotBeSpringComponents_failsForBad() {
    assertThrows(
        AssertionError.class,
        () -> AiPersimmonDddRules.invariantsShouldNotBeSpringComponents().check(BAD));
  }

  @Test
  void domainBuildingBlocksShouldResideInDomain_passesForGood() {
    assertDoesNotThrow(
        () -> AiPersimmonDddRules.domainBuildingBlocksShouldResideInDomain().check(GOOD));
  }

  @Test
  void domainBuildingBlocksShouldResideInDomain_failsForBad() {
    assertThrows(
        AssertionError.class,
        () -> AiPersimmonDddRules.domainBuildingBlocksShouldResideInDomain().check(BAD));
  }

  @Test
  void domainServicesShouldResideInDomain_passesForGood() {
    assertDoesNotThrow(() -> AiPersimmonDddRules.domainServicesShouldResideInDomain().check(GOOD));
  }

  @Test
  void domainServicesShouldResideInDomain_failsForBad() {
    assertThrows(
        AssertionError.class,
        () -> AiPersimmonDddRules.domainServicesShouldResideInDomain().check(BAD));
  }

  @Test
  void aggregateRootsShouldExtendAbstractAggregateRoot_passesForGood() {
    assertDoesNotThrow(
        () -> AiPersimmonDddRules.aggregateRootsShouldExtendAbstractAggregateRoot().check(GOOD));
  }

  @Test
  void aggregateRootsShouldExtendAbstractAggregateRoot_failsForBad() {
    assertThrows(
        AssertionError.class,
        () -> AiPersimmonDddRules.aggregateRootsShouldExtendAbstractAggregateRoot().check(BAD));
  }

  @Test
  void valueObjectsShouldBeImmutable_passesForGood() {
    assertDoesNotThrow(() -> AiPersimmonDddRules.valueObjectsShouldBeImmutable().check(GOOD));
  }

  @Test
  void valueObjectsShouldBeImmutable_failsForBad() {
    assertThrows(
        AssertionError.class, () -> AiPersimmonDddRules.valueObjectsShouldBeImmutable().check(BAD));
  }

  @Test
  void illegalStateTransitionsShouldOnlyComeFromTransitions_passesForGood() {
    assertDoesNotThrow(
        () ->
            AiPersimmonDddRules.illegalStateTransitionsShouldOnlyComeFromTransitions().check(GOOD));
  }

  @Test
  void illegalStateTransitionsShouldOnlyComeFromTransitions_failsForBad() {
    assertThrows(
        AssertionError.class,
        () ->
            AiPersimmonDddRules.illegalStateTransitionsShouldOnlyComeFromTransitions().check(BAD));
  }

  @Test
  void errorCodesShouldBeEnums_passesForGood() {
    assertDoesNotThrow(() -> AiPersimmonDddRules.errorCodesShouldBeEnums().check(GOOD));
  }

  @Test
  void errorCodesShouldBeEnums_failsForBad() {
    assertThrows(
        AssertionError.class, () -> AiPersimmonDddRules.errorCodesShouldBeEnums().check(BAD));
  }

  @Test
  void repositoryPortsShouldBeInterfacesInDomain_passesForGood() {
    assertDoesNotThrow(
        () -> AiPersimmonDddRules.repositoryPortsShouldBeInterfacesInDomain().check(GOOD));
  }

  @Test
  void repositoryPortsShouldBeInterfacesInDomain_failsForBad() {
    assertThrows(
        AssertionError.class,
        () -> AiPersimmonDddRules.repositoryPortsShouldBeInterfacesInDomain().check(BAD));
  }

  @Test
  void repositoryImplementationsShouldResideInInfrastructure_passesForGood() {
    assertDoesNotThrow(
        () ->
            AiPersimmonDddRules.repositoryImplementationsShouldResideInInfrastructure()
                .check(GOOD));
  }

  @Test
  void repositoryImplementationsShouldResideInInfrastructure_failsForBad() {
    assertThrows(
        AssertionError.class,
        () ->
            AiPersimmonDddRules.repositoryImplementationsShouldResideInInfrastructure().check(BAD));
  }

  @Test
  void repositoryImplementationsShouldBeSpringRepositories_passesForGood() {
    assertDoesNotThrow(
        () ->
            AiPersimmonDddRules.repositoryImplementationsShouldBeSpringRepositories().check(GOOD));
  }

  @Test
  void repositoryImplementationsShouldBeSpringRepositories_failsForBad() {
    assertThrows(
        AssertionError.class,
        () -> AiPersimmonDddRules.repositoryImplementationsShouldBeSpringRepositories().check(BAD));
  }

  @Test
  void integrationEventsShouldResideInApi_passesForGood() {
    assertDoesNotThrow(() -> AiPersimmonDddRules.integrationEventsShouldResideInApi().check(GOOD));
  }

  @Test
  void integrationEventsShouldResideInApi_failsForBad() {
    assertThrows(
        AssertionError.class,
        () -> AiPersimmonDddRules.integrationEventsShouldResideInApi().check(BAD));
  }

  @Test
  void integrationEventsShouldDeclareEventType_passesForGood() {
    assertDoesNotThrow(
        () -> AiPersimmonDddRules.integrationEventsShouldDeclareEventType().check(GOOD));
  }

  @Test
  void integrationEventsShouldDeclareEventType_failsForBad() {
    assertThrows(
        AssertionError.class,
        () -> AiPersimmonDddRules.integrationEventsShouldDeclareEventType().check(BAD));
  }

  @Test
  void boundedContextsShouldOnlyDependOnEachOthersApi_passesForGood() {
    assertDoesNotThrow(
        () ->
            AiPersimmonDddRules.boundedContextsShouldOnlyDependOnEachOthersApi(CONTEXTS_GOOD_BASE)
                .check(CONTEXTS_GOOD));
  }

  @Test
  void boundedContextsShouldOnlyDependOnEachOthersApi_failsForBad() {
    assertThrows(
        AssertionError.class,
        () ->
            AiPersimmonDddRules.boundedContextsShouldOnlyDependOnEachOthersApi(CONTEXTS_BAD_BASE)
                .check(CONTEXTS_BAD));
  }

  @Test
  void all_passesForGood() {
    assertDoesNotThrow(() -> AiPersimmonDddRules.all().check(GOOD));
  }
}

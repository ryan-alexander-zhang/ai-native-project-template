package com.aipersimmon.ddd.archunit;

import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;

/**
 * The bundle entry point for the reusable ArchUnit DDD rules. {@link #all()} combines the
 * framework-agnostic rules into a single {@link ArchRule} to wire into a test; the individual rules
 * (and the opt-in ones {@code all()} leaves out) live on the grouped rule classes in this package.
 *
 * <p>They match layers by the package segment they live in ({@code ..domain..}, {@code
 * ..application..}, {@code ..infrastructure..}, {@code ..adapter..}), so they hold whether a layer
 * is a sub-package (single deployable) or its own module (multi-module build) — the segment is
 * present either way. The newest rules accept {@code ..interfaces..} as a second spelling of the
 * interface layer (see {@link Layers}); the older ones do not yet. Every rule tolerates an empty
 * match, so a project that has not yet introduced a given layer or construct still passes rather
 * than erroring.
 *
 * <pre>{@code
 * @AnalyzeClasses(packages = "com.example.app")
 * class ArchitectureTest {
 *     @ArchTest static final ArchRule ddd = AiPersimmonDddRules.all();
 * }
 * }</pre>
 *
 * <h2>Where each rule lives</h2>
 *
 * <ul>
 *   <li>{@link LayeringRules} — layer-dependency direction, the domain's framework freedom, and its
 *       confinement to the framework's core module; also the opt-in {@link
 *       LayeringRules#adapterShouldNotDependOnDomain()}.
 *   <li>{@link EventRules} — domain- and integration-event placement, event-listener placement, and
 *       {@code @EventType} validity; also the opt-in {@link
 *       EventRules#integrationEventsShouldResideInApi()}.
 *   <li>{@link CqrsRules} — command/query handlers, the {@code sendAs} restriction, and the read
 *       side's separation from the write model.
 *   <li>{@link BuildingBlockRules} — aggregate/entity/value-object/domain-service placement,
 *       value-object immutability and value equality, and aggregate-to-aggregate references.
 *   <li>{@link DeterminismRules} — the domain's freedom from ambient time and randomness; also the
 *       opt-in {@link DeterminismRules#applicationShouldNotUseAmbientTimeOrRandomness()}.
 *   <li>{@link RepositoryRules} — repository ports, their implementations, and who may hold a port;
 *       also the opt-in {@link RepositoryRules#implementationsShouldBeSpringRepositories()}.
 *   <li>{@link PersistenceRules} — mappers and mapped row types confined to infrastructure.
 *   <li>{@link InjectionRules} — constructor injection rather than field or setter injection.
 *   <li>{@link WebRules} — what a controller's method signature may name.
 *   <li>{@link InvariantAndErrorRules} — invariants, state transitions, and error codes.
 *   <li>{@link OperationLogRules} — the domain's freedom from the Operation Log component and the
 *       {@code @OperationLog} annotation's placement on application commands.
 *   <li>{@link ProcessRules} — the purity of a {@code ProcessDefinition}.
 *   <li>{@link BoundedContextRules} — the parameterised cross-context isolation rule (opt-in).
 * </ul>
 *
 * <p>The opt-in rules are excluded from {@link #all()} because each presumes something {@code
 * all()} must not — a stricter layout, a specific framework, a packaging convention, or a parameter
 * — so a project adopts them deliberately, alongside {@code all()}.
 *
 * <p>{@link PackageInfoChecks} is a separate, source-level companion (a {@code package-info.java}
 * without annotations produces no class file, so bytecode analysis cannot see it); wire it into a
 * plain {@code @Test}.
 */
public final class AiPersimmonDddRules {

  private AiPersimmonDddRules() {}

  /** All of the framework-agnostic rules, combined into a single rule. */
  public static ArchRule all() {
    return CompositeArchRule.of(LayeringRules.domainShouldNotDependOnOuterLayers())
        .and(LayeringRules.applicationShouldNotDependOnInfrastructureOrInterface())
        .and(LayeringRules.domainShouldBeFrameworkFree())
        .and(LayeringRules.domainShouldDependOnTheFrameworkCoreOnly())
        .and(LayeringRules.domainShouldNotDependOnApiDocumentation())
        .and(DeterminismRules.domainShouldNotUseAmbientTimeOrRandomness())
        .and(EventRules.domainEventsShouldStayInDomain())
        .and(EventRules.domainEventListenersShouldResideInApplicationOrDomain())
        .and(EventRules.integrationEventListenersShouldResideInAdapter())
        .and(EventRules.domainEventListenersShouldBeAnnotatedWithDomainEventHandler())
        .and(EventRules.integrationEventsShouldDeclareEventType())
        .and(EventRules.externalizedShouldOnlyAnnotateIntegrationEvents())
        .and(CqrsRules.commandHandlersShouldNotDependOnOtherCommandHandlers())
        .and(CqrsRules.commandHandlersAndApplicationShouldNotCallSendAs())
        .and(CqrsRules.commandAndQueryHandlersShouldResideInApplication())
        .and(CqrsRules.commandsAndQueriesShouldResideInApplication())
        .and(CqrsRules.readModelsShouldBeProjectionShapes())
        .and(CqrsRules.queryResultsShouldNotBeAggregatesOrEntities())
        .and(InvariantAndErrorRules.invariantsShouldResideInDomain())
        .and(InvariantAndErrorRules.invariantViolationsShouldOnlyComeFromCheckInvariant())
        .and(InvariantAndErrorRules.invariantsShouldNotBeSpringComponents())
        .and(BuildingBlockRules.domainBuildingBlocksShouldResideInDomain())
        .and(BuildingBlockRules.domainServicesShouldResideInDomain())
        .and(BuildingBlockRules.aggregateRootsShouldExtendAbstractAggregateRoot())
        .and(BuildingBlockRules.valueObjectsShouldBeImmutable())
        .and(BuildingBlockRules.valueObjectsShouldDeclareValueEquality())
        .and(BuildingBlockRules.aggregatesShouldReferenceOtherAggregatesByIdentity())
        .and(BuildingBlockRules.versionWitnessIsAdvancedOnlyByPersistenceAdapters())
        .and(InvariantAndErrorRules.illegalStateTransitionsShouldOnlyComeFromTransitions())
        .and(InvariantAndErrorRules.errorCodesShouldBeEnums())
        .and(RepositoryRules.portsShouldBeInterfacesInDomain())
        .and(RepositoryRules.implementationsShouldResideInInfrastructure())
        .and(RepositoryRules.portsShouldNotBeUsedByInboundAdapters())
        .and(PersistenceRules.persistenceMappingsShouldStayInInfrastructure())
        .and(InjectionRules.dependenciesShouldBeConstructorInjected())
        .and(WebRules.controllerSignaturesShouldNotExposeTheDomain())
        .and(OperationLogRules.domainShouldNotDependOnOperationLog())
        .and(OperationLogRules.operationLogShouldOnlyAnnotateApplicationCommands())
        .and(ProcessRules.processDefinitionsShouldBePure());
  }
}

package com.aipersimmon.ddd.processmanager.engine.autoconfigure;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.observability.NoOpStoreAndForwardTracer;
import com.aipersimmon.ddd.observability.NoOpTracer;
import com.aipersimmon.ddd.observability.StoreAndForwardTracer;
import com.aipersimmon.ddd.observability.Tracer;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodec;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodecRegistry;
import com.aipersimmon.ddd.processmanager.codec.ProcessStateCodec;
import com.aipersimmon.ddd.processmanager.codec.ProcessStateCodecRegistry;
import com.aipersimmon.ddd.processmanager.definition.ProcessDefinition;
import com.aipersimmon.ddd.processmanager.definition.ProcessDefinitionRegistry;
import com.aipersimmon.ddd.processmanager.engine.deadline.ProcessDeadlineWorker;
import com.aipersimmon.ddd.processmanager.engine.lease.ProcessClaimStrategy;
import com.aipersimmon.ddd.processmanager.engine.observe.ProcessBacklog;
import com.aipersimmon.ddd.processmanager.engine.observe.ProcessObserver;
import com.aipersimmon.ddd.processmanager.engine.operation.ProcessOperations;
import com.aipersimmon.ddd.processmanager.engine.relay.CommandEffectDispatcher;
import com.aipersimmon.ddd.processmanager.engine.relay.EffectDispatcherRegistry;
import com.aipersimmon.ddd.processmanager.engine.relay.IntegrationEventEffectDispatcher;
import com.aipersimmon.ddd.processmanager.engine.relay.ProcessEffectDispatcher;
import com.aipersimmon.ddd.processmanager.engine.relay.ProcessEffectRelay;
import com.aipersimmon.ddd.processmanager.engine.retry.ExponentialBackoffPolicy;
import com.aipersimmon.ddd.processmanager.engine.retry.ProcessRetryPolicy;
import com.aipersimmon.ddd.processmanager.engine.runtime.DefaultProcessQuery;
import com.aipersimmon.ddd.processmanager.engine.runtime.DefaultProcessRuntime;
import com.aipersimmon.ddd.processmanager.engine.runtime.DuplicateBusinessKeyPolicy;
import com.aipersimmon.ddd.processmanager.engine.runtime.MaxLifetimeExceededCodec;
import com.aipersimmon.ddd.processmanager.engine.runtime.ProcessUnitOfWork;
import com.aipersimmon.ddd.processmanager.engine.runtime.SpringTxProcessUnitOfWork;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessDeadlineStore;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessEffectStore;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessInstanceStore;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessTransitionStore;
import com.aipersimmon.ddd.processmanager.runtime.ProcessRuntime;
import java.time.Clock;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Assembles the durable Process Manager on top of whatever storage backend is present: it collects
 * the consumer's explicitly-registered Definitions, Codecs, and Dispatchers into their registries
 * (which fail fast on a conflict), and — once a backend has contributed the four store beans, a
 * {@link ProcessClaimStrategy}, and a transaction manager — wires the runtime, query, operations,
 * relay, and deadline worker over those ports and runs the workers on their own thread pools. Every
 * bean is {@link ConditionalOnMissingBean}, so a consumer overrides any of them. It scans no
 * business packages and never executes DDL.
 *
 * <p>A storage backend ({@code aipersimmon-ddd-process-manager-jdbc}, {@code
 * aipersimmon-ddd-process-manager-mybatis-plus}) declares its store/claim beans and is ordered
 * before this class, so the {@link ConditionalOnBean} gates below see them.
 */
@AutoConfiguration(after = DataSourceTransactionManagerAutoConfiguration.class)
@ConditionalOnProperty(
    prefix = "aipersimmon.ddd.process-manager",
    name = "enabled",
    matchIfMissing = true)
@EnableConfigurationProperties(ProcessManagerProperties.class)
public class AipersimmonDddProcessManagerAutoConfiguration {

  public AipersimmonDddProcessManagerAutoConfiguration(ProcessManagerProperties properties) {
    properties.validate();
  }

  @Bean
  @ConditionalOnMissingBean(name = "processManagerClock")
  public Clock processManagerClock() {
    return Clock.systemUTC();
  }

  @Bean
  @ConditionalOnMissingBean
  public ProcessDefinitionRegistry processDefinitionRegistry(
      ObjectProvider<ProcessDefinition<?>> definitions) {
    return new ProcessDefinitionRegistry(definitions.orderedStream().toList());
  }

  @Bean
  @ConditionalOnMissingBean
  public ProcessPayloadCodecRegistry processPayloadCodecRegistry(
      ObjectProvider<ProcessPayloadCodec<?>> codecs) {
    return new ProcessPayloadCodecRegistry(codecs.orderedStream().toList());
  }

  @Bean
  @ConditionalOnMissingBean
  public ProcessStateCodecRegistry processStateCodecRegistry(
      ObjectProvider<ProcessStateCodec<?>> codecs) {
    return new ProcessStateCodecRegistry(codecs.orderedStream().toList());
  }

  /**
   * The Spring-transaction unit of work every backend runs its atomic advance/claim/fire through.
   * Shared here (not per backend) because both back the store on a {@code DataSource} with a {@code
   * PlatformTransactionManager}.
   */
  @Bean
  @ConditionalOnBean(PlatformTransactionManager.class)
  @ConditionalOnMissingBean
  public ProcessUnitOfWork processUnitOfWork(PlatformTransactionManager transactionManager) {
    return new SpringTxProcessUnitOfWork(transactionManager);
  }

  @Bean
  @ConditionalOnBean({
    ProcessInstanceStore.class,
    ProcessTransitionStore.class,
    ProcessEffectStore.class,
    ProcessDeadlineStore.class,
    ProcessUnitOfWork.class
  })
  @ConditionalOnMissingBean(ProcessRuntime.class)
  public DefaultProcessRuntime processRuntime(
      ProcessInstanceStore instances,
      ProcessTransitionStore transitions,
      ProcessEffectStore effects,
      ProcessDeadlineStore deadlines,
      ProcessDefinitionRegistry definitions,
      ProcessPayloadCodecRegistry payloadCodecs,
      ProcessStateCodecRegistry stateCodecs,
      ProcessUnitOfWork unitOfWork,
      Clock processManagerClock,
      ProcessManagerProperties properties,
      ObjectProvider<ProcessObserver> observer,
      ObjectProvider<Tracer> tracer,
      ObjectProvider<StoreAndForwardTracer> storeTracer) {
    DuplicateBusinessKeyPolicy policy =
        DuplicateBusinessKeyPolicy.valueOf(
            properties.getStartDuplicateBusinessKey().toUpperCase(Locale.ROOT));
    return new DefaultProcessRuntime(
        instances,
        transitions,
        effects,
        deadlines,
        definitions,
        payloadCodecs,
        stateCodecs,
        unitOfWork,
        processManagerClock,
        randomIds(),
        policy,
        properties.getConcurrencyMaxRetries(),
        observer.getIfAvailable(() -> ProcessObserver.NOOP),
        properties.getInstance().maxLifetimeDuration(),
        properties.getPayload().getMaxBytes(),
        tracer.getIfAvailable(() -> NoOpTracer.INSTANCE),
        storeTracer.getIfAvailable(() -> NoOpStoreAndForwardTracer.INSTANCE));
  }

  /**
   * The built-in codec for the runtime's max-lifetime backstop input, so the backstop deadline can
   * be encoded/decoded. A framework codec, not a business one; a consumer that defines their own
   * for the reserved type overrides it.
   */
  @Bean
  @ConditionalOnMissingBean(MaxLifetimeExceededCodec.class)
  public MaxLifetimeExceededCodec maxLifetimeExceededCodec() {
    return new MaxLifetimeExceededCodec();
  }

  @Bean
  @ConditionalOnBean({
    ProcessEffectStore.class,
    ProcessDeadlineStore.class,
    ProcessInstanceStore.class
  })
  @ConditionalOnMissingBean
  public ProcessBacklog processBacklog(
      ProcessEffectStore effects,
      ProcessDeadlineStore deadlines,
      ProcessInstanceStore instances,
      Clock processManagerClock) {
    return new ProcessBacklog(effects, deadlines, instances, processManagerClock);
  }

  @Bean
  @ConditionalOnBean({
    ProcessInstanceStore.class,
    ProcessTransitionStore.class,
    ProcessEffectStore.class,
    ProcessDeadlineStore.class
  })
  @ConditionalOnMissingBean
  public DefaultProcessQuery processQuery(
      ProcessInstanceStore instances,
      ProcessTransitionStore transitions,
      ProcessEffectStore effects,
      ProcessDeadlineStore deadlines,
      Clock processManagerClock) {
    return new DefaultProcessQuery(instances, transitions, effects, deadlines, processManagerClock);
  }

  @Bean
  @ConditionalOnBean(ProcessRuntime.class)
  @ConditionalOnMissingBean
  public ProcessOperations processOperations(
      ProcessInstanceStore instances,
      ProcessTransitionStore transitions,
      ProcessEffectStore effects,
      ProcessDeadlineStore deadlines,
      ProcessRuntime runtime,
      ProcessPayloadCodecRegistry payloadCodecs,
      ProcessUnitOfWork unitOfWork,
      Clock processManagerClock) {
    return new ProcessOperations(
        instances,
        transitions,
        effects,
        deadlines,
        runtime,
        payloadCodecs,
        unitOfWork,
        processManagerClock,
        randomIds());
  }

  @Bean
  @ConditionalOnBean(CommandBus.class)
  @ConditionalOnMissingBean
  public CommandEffectDispatcher commandEffectDispatcher(CommandBus commandBus) {
    return new CommandEffectDispatcher(commandBus);
  }

  @Bean
  @ConditionalOnBean(IntegrationEvents.class)
  @ConditionalOnMissingBean
  public IntegrationEventEffectDispatcher integrationEventEffectDispatcher(
      IntegrationEvents integrationEvents) {
    return new IntegrationEventEffectDispatcher(integrationEvents);
  }

  @Bean
  @ConditionalOnMissingBean
  public EffectDispatcherRegistry effectDispatcherRegistry(
      ObjectProvider<ProcessEffectDispatcher> dispatchers) {
    return new EffectDispatcherRegistry(dispatchers.orderedStream().toList());
  }

  @Bean
  @ConditionalOnBean({
    ProcessClaimStrategy.class,
    ProcessEffectStore.class,
    ProcessInstanceStore.class
  })
  @ConditionalOnMissingBean
  @ConditionalOnProperty(
      prefix = "aipersimmon.ddd.process-manager.effect-relay",
      name = "enabled",
      matchIfMissing = true)
  public ProcessEffectRelay processEffectRelay(
      ProcessClaimStrategy claimStrategy,
      ProcessEffectStore effects,
      ProcessInstanceStore instances,
      ProcessPayloadCodecRegistry payloadCodecs,
      EffectDispatcherRegistry dispatchers,
      ProcessUnitOfWork unitOfWork,
      Clock processManagerClock,
      ProcessManagerProperties properties,
      ObjectProvider<ProcessObserver> observer,
      ObjectProvider<StoreAndForwardTracer> storeTracer) {
    ProcessManagerProperties.Worker cfg = properties.getEffectRelay();
    return new ProcessEffectRelay(
        claimStrategy,
        effects,
        instances,
        payloadCodecs,
        dispatchers,
        unitOfWork,
        backoff(cfg),
        processManagerClock,
        cfg.getBatchSize(),
        cfg.getLeaseDuration(),
        randomIds(),
        observer.getIfAvailable(() -> ProcessObserver.NOOP),
        storeTracer.getIfAvailable(() -> NoOpStoreAndForwardTracer.INSTANCE));
  }

  @Bean
  @ConditionalOnBean({
    ProcessClaimStrategy.class,
    ProcessDeadlineStore.class,
    ProcessInstanceStore.class,
    ProcessRuntime.class
  })
  @ConditionalOnMissingBean
  @ConditionalOnProperty(
      prefix = "aipersimmon.ddd.process-manager.deadline-worker",
      name = "enabled",
      matchIfMissing = true)
  public ProcessDeadlineWorker processDeadlineWorker(
      ProcessClaimStrategy claimStrategy,
      ProcessDeadlineStore deadlines,
      ProcessInstanceStore instances,
      ProcessPayloadCodecRegistry payloadCodecs,
      ProcessRuntime runtime,
      ProcessUnitOfWork unitOfWork,
      Clock processManagerClock,
      ProcessManagerProperties properties,
      ObjectProvider<StoreAndForwardTracer> storeTracer) {
    ProcessManagerProperties.Worker cfg = properties.getDeadlineWorker();
    return new ProcessDeadlineWorker(
        claimStrategy,
        deadlines,
        instances,
        payloadCodecs,
        runtime,
        unitOfWork,
        backoff(cfg),
        processManagerClock,
        cfg.getBatchSize(),
        cfg.getLeaseDuration(),
        randomIds(),
        storeTracer.getIfAvailable(() -> NoOpStoreAndForwardTracer.INSTANCE));
  }

  @Bean
  @ConditionalOnMissingBean
  public ProcessWorkerScheduler processWorkerScheduler(
      ObjectProvider<ProcessEffectRelay> relay,
      ObjectProvider<ProcessDeadlineWorker> worker,
      ProcessManagerProperties properties) {
    return new ProcessWorkerScheduler(
        relay.getIfAvailable(),
        properties.getEffectRelay().getPollDelay(),
        worker.getIfAvailable(),
        properties.getDeadlineWorker().getPollDelay(),
        properties.getShutdownTimeout());
  }

  @Bean
  @ConditionalOnBean(ProcessInstanceStore.class)
  @ConditionalOnMissingBean
  public ProcessManagerStartupValidator processManagerStartupValidator(
      ProcessInstanceStore instances,
      ProcessDefinitionRegistry definitions,
      ProcessStateCodecRegistry stateCodecs,
      ProcessPayloadCodecRegistry payloadCodecs,
      EffectDispatcherRegistry dispatchers,
      ProcessManagerProperties properties) {
    return new ProcessManagerStartupValidator(
        instances,
        definitions,
        stateCodecs,
        payloadCodecs,
        dispatchers,
        properties.getEffectRelay().isEnabled());
  }

  private static ProcessRetryPolicy backoff(ProcessManagerProperties.Worker cfg) {
    ProcessManagerProperties.Backoff b = cfg.getBackoff();
    return new ExponentialBackoffPolicy(
        b.getInitial(), b.getMax(), b.getMultiplier(), b.getJitter(), cfg.getMaxAttempts());
  }

  private static Supplier<String> randomIds() {
    return () -> UUID.randomUUID().toString();
  }
}

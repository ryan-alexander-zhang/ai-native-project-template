package com.aipersimmon.ddd.processmanager.jdbc.autoconfigure;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.observability.NoOpTracer;
import com.aipersimmon.ddd.observability.Tracer;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodec;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodecRegistry;
import com.aipersimmon.ddd.processmanager.codec.ProcessStateCodec;
import com.aipersimmon.ddd.processmanager.codec.ProcessStateCodecRegistry;
import com.aipersimmon.ddd.processmanager.definition.ProcessDefinition;
import com.aipersimmon.ddd.processmanager.definition.ProcessDefinitionRegistry;
import com.aipersimmon.ddd.processmanager.jdbc.deadline.JdbcProcessDeadlineWorker;
import com.aipersimmon.ddd.processmanager.jdbc.lease.JdbcProcessDialect;
import com.aipersimmon.ddd.processmanager.jdbc.lease.WorkerId;
import com.aipersimmon.ddd.processmanager.jdbc.observe.JdbcProcessBacklog;
import com.aipersimmon.ddd.processmanager.jdbc.observe.ProcessObserver;
import com.aipersimmon.ddd.processmanager.jdbc.operation.JdbcProcessOperations;
import com.aipersimmon.ddd.processmanager.jdbc.relay.CommandEffectDispatcher;
import com.aipersimmon.ddd.processmanager.jdbc.relay.EffectDispatcherRegistry;
import com.aipersimmon.ddd.processmanager.jdbc.relay.IntegrationEventEffectDispatcher;
import com.aipersimmon.ddd.processmanager.jdbc.relay.JdbcProcessEffectRelay;
import com.aipersimmon.ddd.processmanager.jdbc.relay.ProcessEffectDispatcher;
import com.aipersimmon.ddd.processmanager.jdbc.retry.ExponentialBackoffPolicy;
import com.aipersimmon.ddd.processmanager.jdbc.retry.ProcessRetryPolicy;
import com.aipersimmon.ddd.processmanager.jdbc.runtime.DuplicateBusinessKeyPolicy;
import com.aipersimmon.ddd.processmanager.jdbc.runtime.JdbcProcessQuery;
import com.aipersimmon.ddd.processmanager.jdbc.runtime.JdbcProcessRuntime;
import com.aipersimmon.ddd.processmanager.jdbc.runtime.JdbcProcessUnitOfWork;
import com.aipersimmon.ddd.processmanager.jdbc.runtime.MaxLifetimeExceededCodec;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessDeadlineStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessEffectStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessInstanceStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessTransitionStore;
import java.time.Clock;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Assembles the JDBC Process Manager once a {@code JdbcTemplate} is available: it collects
 * the consumer's explicitly-registered Definitions, Codecs, and Dispatchers into their
 * registries (which fail fast on a conflict), picks the SQL dialect, wires the runtime,
 * query, relay, deadline worker, and operations, and runs the workers on their own thread
 * pools. Every bean is {@link ConditionalOnMissingBean}, so a consumer overrides any of
 * them. It scans no business packages and never executes DDL.
 */
@AutoConfiguration(after = {
        JdbcTemplateAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class})
@ConditionalOnClass(JdbcTemplate.class)
@ConditionalOnProperty(prefix = "aipersimmon.ddd.process-manager.jdbc", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(ProcessManagerJdbcProperties.class)
public class AipersimmonDddProcessManagerJdbcAutoConfiguration {

    public AipersimmonDddProcessManagerJdbcAutoConfiguration(ProcessManagerJdbcProperties properties) {
        properties.validate();
    }

    @Bean
    @ConditionalOnMissingBean(name = "processManagerClock")
    public Clock processManagerClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkerId processWorkerId(ProcessManagerJdbcProperties properties) {
        String configured = properties.getWorkerId();
        return (configured == null || configured.isBlank()) ? WorkerId.generate() : new WorkerId(configured);
    }

    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean
    public JdbcProcessDialect processDialect(ProcessManagerJdbcProperties properties, DataSource dataSource) {
        return ProcessDialectFactory.create(properties.getDialect(), dataSource);
    }

    @Bean
    @ConditionalOnMissingBean
    public ProcessDefinitionRegistry processDefinitionRegistry(ObjectProvider<ProcessDefinition<?>> definitions) {
        return new ProcessDefinitionRegistry(definitions.orderedStream().toList());
    }

    @Bean
    @ConditionalOnMissingBean
    public ProcessPayloadCodecRegistry processPayloadCodecRegistry(ObjectProvider<ProcessPayloadCodec<?>> codecs) {
        return new ProcessPayloadCodecRegistry(codecs.orderedStream().toList());
    }

    @Bean
    @ConditionalOnMissingBean
    public ProcessStateCodecRegistry processStateCodecRegistry(ObjectProvider<ProcessStateCodec<?>> codecs) {
        return new ProcessStateCodecRegistry(codecs.orderedStream().toList());
    }

    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    @ConditionalOnMissingBean
    public JdbcProcessInstanceStore jdbcProcessInstanceStore(JdbcTemplate jdbcTemplate) {
        return new JdbcProcessInstanceStore(jdbcTemplate);
    }

    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    @ConditionalOnMissingBean
    public JdbcProcessTransitionStore jdbcProcessTransitionStore(JdbcTemplate jdbcTemplate) {
        return new JdbcProcessTransitionStore(jdbcTemplate);
    }

    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    @ConditionalOnMissingBean
    public JdbcProcessEffectStore jdbcProcessEffectStore(JdbcTemplate jdbcTemplate) {
        return new JdbcProcessEffectStore(jdbcTemplate);
    }

    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    @ConditionalOnMissingBean
    public JdbcProcessDeadlineStore jdbcProcessDeadlineStore(JdbcTemplate jdbcTemplate) {
        return new JdbcProcessDeadlineStore(jdbcTemplate);
    }

    @Bean
    @ConditionalOnBean(PlatformTransactionManager.class)
    @ConditionalOnMissingBean
    public JdbcProcessUnitOfWork jdbcProcessUnitOfWork(PlatformTransactionManager transactionManager) {
        return new JdbcProcessUnitOfWork(transactionManager);
    }

    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    @ConditionalOnMissingBean
    public JdbcProcessRuntime jdbcProcessRuntime(
            JdbcProcessInstanceStore instances, JdbcProcessTransitionStore transitions,
            JdbcProcessEffectStore effects, JdbcProcessDeadlineStore deadlines,
            ProcessDefinitionRegistry definitions, ProcessPayloadCodecRegistry payloadCodecs,
            ProcessStateCodecRegistry stateCodecs, JdbcProcessUnitOfWork unitOfWork, Clock processManagerClock,
            ProcessManagerJdbcProperties properties, ObjectProvider<ProcessObserver> observer,
            ObjectProvider<Tracer> tracer) {
        DuplicateBusinessKeyPolicy policy = DuplicateBusinessKeyPolicy.valueOf(
                properties.getStartDuplicateBusinessKey().toUpperCase(Locale.ROOT));
        return new JdbcProcessRuntime(
                instances, transitions, effects, deadlines, definitions, payloadCodecs, stateCodecs,
                unitOfWork, processManagerClock, randomIds(), policy, properties.getConcurrencyMaxRetries(),
                observer.getIfAvailable(() -> ProcessObserver.NOOP),
                properties.getInstance().maxLifetimeDuration(), properties.getPayload().getMaxBytes(),
                tracer.getIfAvailable(() -> NoOpTracer.INSTANCE));
    }

    /**
     * The built-in codec for the runtime's max-lifetime backstop input, registered so the backstop
     * deadline can be encoded/decoded. It is a framework codec, not a business one; a consumer that
     * defines their own for the reserved type overrides it.
     */
    @Bean
    @ConditionalOnMissingBean(MaxLifetimeExceededCodec.class)
    public MaxLifetimeExceededCodec maxLifetimeExceededCodec() {
        return new MaxLifetimeExceededCodec();
    }

    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    @ConditionalOnMissingBean
    public JdbcProcessBacklog jdbcProcessBacklog(
            JdbcProcessEffectStore effects, JdbcProcessDeadlineStore deadlines,
            JdbcProcessInstanceStore instances, Clock processManagerClock) {
        return new JdbcProcessBacklog(effects, deadlines, instances, processManagerClock);
    }

    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    @ConditionalOnMissingBean
    public JdbcProcessQuery jdbcProcessQuery(
            JdbcProcessInstanceStore instances, JdbcProcessTransitionStore transitions,
            JdbcProcessEffectStore effects, JdbcProcessDeadlineStore deadlines, Clock processManagerClock) {
        return new JdbcProcessQuery(instances, transitions, effects, deadlines, processManagerClock);
    }

    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    @ConditionalOnMissingBean
    public JdbcProcessOperations jdbcProcessOperations(
            JdbcProcessInstanceStore instances, JdbcProcessTransitionStore transitions,
            JdbcProcessEffectStore effects, JdbcProcessDeadlineStore deadlines,
            JdbcProcessRuntime runtime, ProcessPayloadCodecRegistry payloadCodecs,
            JdbcProcessUnitOfWork unitOfWork, Clock processManagerClock) {
        return new JdbcProcessOperations(
                instances, transitions, effects, deadlines, runtime, payloadCodecs, unitOfWork,
                processManagerClock, randomIds());
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
    public IntegrationEventEffectDispatcher integrationEventEffectDispatcher(IntegrationEvents integrationEvents) {
        return new IntegrationEventEffectDispatcher(integrationEvents);
    }

    @Bean
    @ConditionalOnMissingBean
    public EffectDispatcherRegistry effectDispatcherRegistry(ObjectProvider<ProcessEffectDispatcher> dispatchers) {
        return new EffectDispatcherRegistry(dispatchers.orderedStream().toList());
    }

    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "aipersimmon.ddd.process-manager.jdbc.effect-relay",
            name = "enabled", matchIfMissing = true)
    public JdbcProcessEffectRelay jdbcProcessEffectRelay(
            JdbcTemplate jdbcTemplate, JdbcProcessDialect dialect, JdbcProcessEffectStore effects,
            JdbcProcessInstanceStore instances, ProcessPayloadCodecRegistry payloadCodecs,
            EffectDispatcherRegistry dispatchers, JdbcProcessUnitOfWork unitOfWork, Clock processManagerClock,
            WorkerId processWorkerId, ProcessManagerJdbcProperties properties,
            ObjectProvider<ProcessObserver> observer) {
        ProcessManagerJdbcProperties.Worker cfg = properties.getEffectRelay();
        return new JdbcProcessEffectRelay(
                jdbcTemplate, dialect, effects, instances, payloadCodecs, dispatchers, unitOfWork,
                backoff(cfg), processManagerClock, processWorkerId, cfg.getBatchSize(),
                cfg.getLeaseDuration(), randomIds(), observer.getIfAvailable(() -> ProcessObserver.NOOP));
    }

    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "aipersimmon.ddd.process-manager.jdbc.deadline-worker",
            name = "enabled", matchIfMissing = true)
    public JdbcProcessDeadlineWorker jdbcProcessDeadlineWorker(
            JdbcTemplate jdbcTemplate, JdbcProcessDialect dialect, JdbcProcessDeadlineStore deadlines,
            JdbcProcessInstanceStore instances, ProcessPayloadCodecRegistry payloadCodecs,
            JdbcProcessRuntime runtime, JdbcProcessUnitOfWork unitOfWork, Clock processManagerClock,
            WorkerId processWorkerId, ProcessManagerJdbcProperties properties) {
        ProcessManagerJdbcProperties.Worker cfg = properties.getDeadlineWorker();
        return new JdbcProcessDeadlineWorker(
                jdbcTemplate, dialect, deadlines, instances, payloadCodecs, runtime, unitOfWork,
                backoff(cfg), processManagerClock, processWorkerId, cfg.getBatchSize(),
                cfg.getLeaseDuration(), randomIds());
    }

    @Bean
    @ConditionalOnMissingBean
    public ProcessWorkerScheduler processWorkerScheduler(
            ObjectProvider<JdbcProcessEffectRelay> relay, ObjectProvider<JdbcProcessDeadlineWorker> worker,
            ProcessManagerJdbcProperties properties) {
        return new ProcessWorkerScheduler(
                relay.getIfAvailable(), properties.getEffectRelay().getPollDelay(),
                worker.getIfAvailable(), properties.getDeadlineWorker().getPollDelay(),
                properties.getShutdownTimeout());
    }

    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "aipersimmon.ddd.process-manager.jdbc",
            name = "schema-validation", havingValue = "validate", matchIfMissing = true)
    public ProcessSchemaValidator processSchemaValidator(JdbcTemplate jdbcTemplate) {
        return new ProcessSchemaValidator(jdbcTemplate);
    }

    @Bean
    @ConditionalOnBean(JdbcProcessInstanceStore.class)
    @ConditionalOnMissingBean
    public ProcessManagerStartupValidator processManagerStartupValidator(
            JdbcProcessInstanceStore instances, ProcessDefinitionRegistry definitions,
            ProcessStateCodecRegistry stateCodecs, ProcessPayloadCodecRegistry payloadCodecs,
            EffectDispatcherRegistry dispatchers, ProcessManagerJdbcProperties properties) {
        return new ProcessManagerStartupValidator(
                instances, definitions, stateCodecs, payloadCodecs, dispatchers,
                properties.getEffectRelay().isEnabled());
    }

    private static ProcessRetryPolicy backoff(ProcessManagerJdbcProperties.Worker cfg) {
        ProcessManagerJdbcProperties.Backoff b = cfg.getBackoff();
        return new ExponentialBackoffPolicy(
                b.getInitial(), b.getMax(), b.getMultiplier(), b.getJitter(), cfg.getMaxAttempts());
    }

    private static Supplier<String> randomIds() {
        return () -> UUID.randomUUID().toString();
    }
}

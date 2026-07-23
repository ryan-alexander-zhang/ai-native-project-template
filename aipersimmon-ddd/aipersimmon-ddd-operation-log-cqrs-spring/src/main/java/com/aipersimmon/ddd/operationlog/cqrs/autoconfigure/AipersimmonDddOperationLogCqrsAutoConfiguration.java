package com.aipersimmon.ddd.operationlog.cqrs.autoconfigure;

import com.aipersimmon.ddd.operationlog.cqrs.capture.CompletedOperationLogInterceptor;
import com.aipersimmon.ddd.operationlog.cqrs.capture.DefaultFailureCompletionPolicy;
import com.aipersimmon.ddd.operationlog.cqrs.capture.FailedOperationLogInterceptor;
import com.aipersimmon.ddd.operationlog.cqrs.capture.FailureCompletionPolicy;
import com.aipersimmon.ddd.operationlog.cqrs.capture.IndependentTransactionRunner;
import com.aipersimmon.ddd.operationlog.cqrs.capture.OperationActorResolver;
import com.aipersimmon.ddd.operationlog.cqrs.capture.OperationLogDefinitionRegistry;
import com.aipersimmon.ddd.operationlog.cqrs.capture.OperationLogInvocationFactory;
import com.aipersimmon.ddd.operationlog.cqrs.capture.OperationTenantResolver;
import com.aipersimmon.ddd.operationlog.cqrs.capture.SpringIndependentTransactionRunner;
import com.aipersimmon.ddd.operationlog.cqrs.capture.TransactionState;
import com.aipersimmon.ddd.operationlog.definition.OperationLogDefinition;
import com.aipersimmon.ddd.operationlog.engine.autoconfigure.AipersimmonDddOperationLogAutoConfiguration;
import com.aipersimmon.ddd.operationlog.engine.autoconfigure.OperationLogProperties;
import com.aipersimmon.ddd.operationlog.engine.observability.OperationLogMetrics;
import com.aipersimmon.ddd.operationlog.port.OperationLogs;
import com.aipersimmon.ddd.operationlog.spi.FailureClassifier;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Wires the Operation Log capture layer. The definition registry (scanning {@code @OperationLog}
 * and collecting hand-written definitions) and the failure-path seams are always available; the
 * invocation factory and the two interceptors bind only when an {@link OperationLogs} pipeline
 * exists (a storage backend is present), and require the consumer's actor/tenant resolvers — a
 * missing resolver then fails fast at startup. Every bean is overridable.
 */
@AutoConfiguration(after = AipersimmonDddOperationLogAutoConfiguration.class)
public class AipersimmonDddOperationLogCqrsAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public OperationLogDefinitionRegistry operationLogDefinitionRegistry(
      ObjectProvider<OperationLogDefinition<?, ?>> definitions, ApplicationContext context) {
    List<OperationLogDefinition<?, ?>> codeDefinitions = definitions.orderedStream().toList();
    List<String> basePackages =
        AutoConfigurationPackages.has(context) ? AutoConfigurationPackages.get(context) : List.of();
    Map<Class<?>, OperationLogDefinition<?, ?>> annotated =
        OperationLogAnnotationScanner.scan(basePackages);
    return OperationLogDefinitionRegistry.build(codeDefinitions, annotated);
  }

  @Bean
  @ConditionalOnMissingBean(FailureCompletionPolicy.class)
  public FailureCompletionPolicy operationLogFailureCompletionPolicy() {
    return new DefaultFailureCompletionPolicy();
  }

  @Bean
  @ConditionalOnMissingBean(TransactionState.class)
  public TransactionState operationLogTransactionState() {
    return TransactionSynchronizationManager::isActualTransactionActive;
  }

  @Bean
  @ConditionalOnBean(PlatformTransactionManager.class)
  @ConditionalOnMissingBean(IndependentTransactionRunner.class)
  public IndependentTransactionRunner operationLogIndependentTransactionRunner(
      PlatformTransactionManager transactionManager) {
    return new SpringIndependentTransactionRunner(transactionManager);
  }

  @Bean
  @ConditionalOnBean(OperationLogs.class)
  @ConditionalOnMissingBean
  public OperationLogInvocationFactory operationLogInvocationFactory(
      OperationLogProperties properties,
      Clock operationLogClock,
      OperationActorResolver actorResolver,
      OperationTenantResolver tenantResolver,
      @Value("${spring.application.name:aipersimmon}") String applicationName) {
    String source = properties.getSource().isBlank() ? applicationName : properties.getSource();
    return new OperationLogInvocationFactory(
        source, operationLogClock, actorResolver, tenantResolver);
  }

  @Bean
  @ConditionalOnBean(OperationLogs.class)
  public CompletedOperationLogInterceptor completedOperationLogInterceptor(
      OperationLogDefinitionRegistry registry,
      OperationLogInvocationFactory invocationFactory,
      OperationLogs operationLogs,
      OperationLogMetrics metrics) {
    return new CompletedOperationLogInterceptor(
        registry, invocationFactory, operationLogs, metrics);
  }

  @Bean
  @ConditionalOnBean(OperationLogs.class)
  public FailedOperationLogInterceptor failedOperationLogInterceptor(
      OperationLogDefinitionRegistry registry,
      OperationLogInvocationFactory invocationFactory,
      OperationLogs operationLogs,
      FailureClassifier failureClassifier,
      FailureCompletionPolicy completionPolicy,
      TransactionState transactionState,
      IndependentTransactionRunner independentTransactionRunner,
      OperationLogMetrics metrics) {
    return new FailedOperationLogInterceptor(
        registry,
        invocationFactory,
        operationLogs,
        failureClassifier,
        completionPolicy,
        transactionState,
        independentTransactionRunner,
        metrics);
  }
}

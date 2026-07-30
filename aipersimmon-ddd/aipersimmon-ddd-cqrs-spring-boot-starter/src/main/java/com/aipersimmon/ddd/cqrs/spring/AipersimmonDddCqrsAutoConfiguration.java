package com.aipersimmon.ddd.cqrs.spring;

import com.aipersimmon.ddd.core.id.IdGenerator;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.aipersimmon.ddd.cqrs.CommandInterceptor;
import com.aipersimmon.ddd.cqrs.QueryBus;
import com.aipersimmon.ddd.cqrs.QueryHandler;
import com.aipersimmon.ddd.cqrs.UnitOfWork;
import jakarta.validation.Validator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Auto-configures the CQRS command and query buses and the built-in interceptor chain when the
 * application does not define its own. The command bus gathers all {@link CommandHandler} beans and
 * {@link CommandInterceptor} beans; the built-in interceptors are logging (always) and transaction
 * (when a transaction manager is present), with validation added when a Bean Validation provider is
 * on the classpath. Applications can add their own interceptors as beans or replace any bean here.
 *
 * <p>"When a transaction manager is present" is a condition, not a fallback: if none is, {@link
 * CommandTransactionGuard} refuses to start rather than letting every command run untransacted
 * unannounced. See {@code aipersimmon.ddd.cqrs.transaction.required}.
 */
@AutoConfiguration(
    after = {
      DataSourceTransactionManagerAutoConfiguration.class,
      TransactionAutoConfiguration.class,
      ValidationAutoConfiguration.class
    })
@EnableConfigurationProperties(AipersimmonDddCqrsProperties.class)
public class AipersimmonDddCqrsAutoConfiguration {

  /**
   * Decides what a missing transaction manager means before anything relies on it. The two beans
   * below that implement "one command, one transaction" are conditional on that manager, so without
   * this guard their absence is the silent loss of the starter's headline guarantee.
   */
  @Bean
  @ConditionalOnMissingBean
  public CommandTransactionGuard commandTransactionGuard(
      ObjectProvider<PlatformTransactionManager> transactionManager,
      AipersimmonDddCqrsProperties properties) {
    return new CommandTransactionGuard(
        transactionManager, properties.getTransaction().isRequired());
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(PlatformTransactionManager.class)
  public UnitOfWork unitOfWork(PlatformTransactionManager transactionManager) {
    return new TransactionTemplateUnitOfWork(new TransactionTemplate(transactionManager));
  }

  @Bean
  @ConditionalOnMissingBean
  public LoggingCommandInterceptor loggingCommandInterceptor() {
    return new LoggingCommandInterceptor();
  }

  @Bean
  @ConditionalOnMissingBean
  public ConcurrencyTranslationCommandInterceptor concurrencyTranslationCommandInterceptor() {
    return new ConcurrencyTranslationCommandInterceptor();
  }

  @Bean
  @ConditionalOnBean(UnitOfWork.class)
  @ConditionalOnMissingBean
  public TransactionCommandInterceptor transactionCommandInterceptor(UnitOfWork unitOfWork) {
    return new TransactionCommandInterceptor(unitOfWork);
  }

  @Bean
  @ConditionalOnMissingBean
  public CommandBus commandBus(
      ObjectProvider<CommandHandler<?, ?>> handlers,
      ObjectProvider<CommandInterceptor> interceptors,
      IdGenerator idGenerator) {
    // Suppliers, not resolved lists: reading the providers here would instantiate every handler
    // while this bean is still being created, and a handler that takes the bus in its constructor
    // would then be handed a half-built one. The bus reads them once the context is complete.
    return new RegistryCommandBus(
        () -> handlers.stream().toList(), () -> interceptors.stream().toList(), idGenerator::newId);
  }

  @Bean
  @ConditionalOnMissingBean
  public QueryBus queryBus(ObjectProvider<QueryHandler<?, ?>> handlers) {
    return new RegistryQueryBus(handlers.stream().toList());
  }

  /**
   * Wires the validation interceptor only when a Bean Validation provider is on the classpath and a
   * {@link Validator} bean is available.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(Validator.class)
  static class ValidationConfiguration {

    @Bean
    @ConditionalOnBean(Validator.class)
    @ConditionalOnMissingBean
    public ValidationCommandInterceptor validationCommandInterceptor(Validator validator) {
      return new ValidationCommandInterceptor(validator);
    }
  }
}

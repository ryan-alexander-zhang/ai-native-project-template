package com.aipersimmon.ddd.cqrs.spring;

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
 */
@AutoConfiguration(
    after = {
      DataSourceTransactionManagerAutoConfiguration.class,
      TransactionAutoConfiguration.class,
      ValidationAutoConfiguration.class
    })
public class AipersimmonDddCqrsAutoConfiguration {

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
      ObjectProvider<CommandInterceptor> interceptors) {
    return new RegistryCommandBus(handlers.stream().toList(), interceptors.stream().toList());
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

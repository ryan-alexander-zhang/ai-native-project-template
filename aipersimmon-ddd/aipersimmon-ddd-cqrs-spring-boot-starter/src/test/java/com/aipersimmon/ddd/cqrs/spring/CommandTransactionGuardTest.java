package com.aipersimmon.ddd.cqrs.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipersimmon.ddd.core.id.IdGenerator;
import com.aipersimmon.ddd.cqrs.UnitOfWork;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * "One command, one transaction" is implemented by two beans that are both conditional on a
 * transaction manager, so its absence used to remove the guarantee without removing anything
 * visible: no bean, no log line, no failure — just commands that no longer roll back together.
 * These tests pin the three outcomes: refuse, or run untransacted because someone said so, or work.
 */
class CommandTransactionGuardTest {

  @Configuration(proxyBeanMethods = false)
  static class Ids {
    @Bean
    IdGenerator idGenerator() {
      return () -> "id-1";
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class WithTransactionManager {
    @Bean
    DataSource dataSource() {
      return new EmbeddedDatabaseBuilder()
          .setType(EmbeddedDatabaseType.H2)
          .generateUniqueName(true)
          .build();
    }

    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
      return new DataSourceTransactionManager(dataSource);
    }
  }

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AipersimmonDddCqrsAutoConfiguration.class))
          .withUserConfiguration(Ids.class);

  @Test
  void noTransactionManagerRefusesToStart() {
    runner.run(
        context ->
            assertThat(context)
                .getFailure()
                .rootCause()
                .isInstanceOf(MissingTransactionManagerException.class)
                .hasMessageContaining("aipersimmon.ddd.cqrs.transaction.required=false"));
  }

  @Test
  void anApplicationMayDeclareThatItRunsWithoutTransactions() {
    runner
        .withPropertyValues("aipersimmon.ddd.cqrs.transaction.required=false")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              // The guarantee really is gone — that is the point of having to say so out loud.
              assertThat(context).doesNotHaveBean(UnitOfWork.class);
              assertThat(context).doesNotHaveBean(TransactionCommandInterceptor.class);
            });
  }

  @Test
  void withATransactionManagerTheUnitOfWorkAndInterceptorAreWired() {
    runner
        .withUserConfiguration(WithTransactionManager.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(UnitOfWork.class);
              assertThat(context).hasSingleBean(TransactionCommandInterceptor.class);
            });
  }
}

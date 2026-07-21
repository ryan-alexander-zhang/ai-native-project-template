package com.aipersimmon.ddd.processmanager.jdbc.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipersimmon.ddd.processmanager.jdbc.runtime.JdbcProcessRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Without a {@code DataSource}/{@code JdbcTemplate}, the whole JDBC Process Manager
 * auto-configuration must back off cleanly rather than fail the application context.
 */
class ProcessManagerJdbcBackoffTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(AipersimmonDddProcessManagerJdbcAutoConfiguration.class));

  @Test
  void backsOffCleanlyWithoutADataSource() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean(JdbcProcessRuntime.class);
        });
  }
}

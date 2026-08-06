package com.aipersimmon.ddd.processmanager.mybatisplus;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipersimmon.ddd.processmanager.engine.runtime.DefaultProcessRuntime;
import com.aipersimmon.ddd.processmanager.mybatisplus.autoconfigure.AipersimmonDddProcessManagerMybatisPlusAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Without a {@code DataSource}/{@code SqlSessionFactory}, the whole MyBatis-Plus Process Manager
 * auto-configuration must back off cleanly rather than fail the application context.
 */
class ProcessManagerMybatisPlusBackoffTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  AipersimmonDddProcessManagerMybatisPlusAutoConfiguration.class));

  @Test
  void backsOffCleanlyWithoutADataSource() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean(DefaultProcessRuntime.class);
        });
  }
}

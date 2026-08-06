package com.aipersimmon.ddd.operationlog.mybatisplus;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Opting in to the audit retention must actually produce the cleanup bean.
 *
 * <p>This is not a formality. The cleanup lives in a nested {@code @Configuration}, and Spring
 * processes a member class before the enclosing class's {@code @Bean} methods — so the obvious
 * {@code @ConditionalOnBean(OperationLogMapper.class)} there is evaluated before the mapper is
 * registered and silently never matches. The symptom is an operator setting {@code cleanup.enabled}
 * and getting no cleanup and no complaint, which the direct-construction cleanup test cannot see
 * because it builds the collaborator itself.
 */
@SpringBootTest(
    classes = OperationLogCleanupWiringTest.TestApp.class,
    properties = {
      "aipersimmon.ddd.operation-log.cleanup.enabled=true",
      // Never let the scheduled purge fire during the test; presence is the whole assertion.
      "aipersimmon.ddd.operation-log.cleanup.poll-delay-ms=3600000",
      // This context applies no migration, and wiring does not read a table.
      "aipersimmon.ddd.operation-log.schema-validation=none"
    })
class OperationLogCleanupWiringTest {

  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class TestApp {}

  @Autowired(required = false)
  MybatisPlusOperationLogCleanup cleanup;

  @Test
  void optingInRegistersTheCleanup() {
    assertNotNull(
        cleanup, "aipersimmon.ddd.operation-log.cleanup.enabled=true must wire a cleanup");
  }
}

package com.aipersimmon.ddd.operationlog.mybatisplus;

import com.aipersimmon.ddd.testsupport.SharedContainers;
import com.aipersimmon.ddd.testsupport.TestDataSources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * The MyBatis-Plus sink on a real PostgreSQL, using the {@code ON CONFLICT DO NOTHING} path so a
 * duplicate inside a transaction does not abort it — behaviorally equivalent to the JDBC backend.
 */
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class MybatisPlusOperationLogSinkPostgresTest {

  @Test
  void sink_behaviour_on_postgres() {
    MybatisPlusSinkScenarios.run(
        TestDataSources.from(SharedContainers.postgres()), "postgresql", true);
  }
}

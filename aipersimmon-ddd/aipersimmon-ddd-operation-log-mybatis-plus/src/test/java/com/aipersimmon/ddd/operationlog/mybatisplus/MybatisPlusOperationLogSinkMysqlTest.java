package com.aipersimmon.ddd.operationlog.mybatisplus;

import com.aipersimmon.ddd.testsupport.SharedContainers;
import com.aipersimmon.ddd.testsupport.TestDataSources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * The MyBatis-Plus sink on a real MySQL, using the catch-based path ({@code postgres=false}) so a
 * duplicate inside a transaction converges without aborting it — behaviorally equivalent to the
 * JDBC backend and to the PostgreSQL {@code ON CONFLICT} path. Also exercises the MySQL {@code V1}
 * DDL end to end (H2's MySQL-compatibility mode is not real MySQL).
 */
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class MybatisPlusOperationLogSinkMysqlTest {

  @Test
  void sink_behaviour_on_mysql() {
    MybatisPlusSinkScenarios.run(TestDataSources.from(SharedContainers.mysql()), "mysql", false);
  }
}

package com.aipersimmon.ddd.operationlog.mybatisplus;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/** The MyBatis-Plus sink on H2 (no Docker): append, duplicate convergence, tx not poisoned. */
class MybatisPlusOperationLogSinkH2Test {

  private static final AtomicInteger DB = new AtomicInteger();

  @Test
  void sink_behaviour_on_h2() {
    SimpleDriverDataSource dataSource =
        new SimpleDriverDataSource(
            new org.h2.Driver(),
            "jdbc:h2:mem:oplog-mp" + DB.incrementAndGet() + ";DB_CLOSE_DELAY=-1",
            "sa",
            "");
    MybatisPlusSinkScenarios.run(dataSource, "h2", false);
  }
}

package com.aipersimmon.ddd.operationlog.cqrs.capture;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/** The full capture pipeline end-to-end on H2 (no Docker). */
class OperationLogEndToEndH2Test {

  private static final AtomicInteger DB = new AtomicInteger();

  @Test
  void capture_pipeline_end_to_end_on_h2() {
    SimpleDriverDataSource dataSource =
        new SimpleDriverDataSource(
            new org.h2.Driver(),
            "jdbc:h2:mem:oplog-e2e" + DB.incrementAndGet() + ";DB_CLOSE_DELAY=-1",
            "sa",
            "");
    OperationLogEndToEndScenarios.run(dataSource, "h2");
  }
}

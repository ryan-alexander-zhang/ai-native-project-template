package com.aipersimmon.ddd.operationlog.cqrs.capture;

import com.aipersimmon.ddd.testsupport.SharedContainers;
import com.aipersimmon.ddd.testsupport.TestDataSources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * The full capture pipeline end-to-end on a real PostgreSQL — also re-proving, at the full-stack
 * level, that the success-path duplicate convergence (ON CONFLICT) does not abort the business
 * transaction.
 */
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class OperationLogEndToEndPostgresTest {

  @Test
  void capture_pipeline_end_to_end_on_postgres() {
    OperationLogEndToEndScenarios.run(
        TestDataSources.from(SharedContainers.postgres()), "postgresql");
  }
}

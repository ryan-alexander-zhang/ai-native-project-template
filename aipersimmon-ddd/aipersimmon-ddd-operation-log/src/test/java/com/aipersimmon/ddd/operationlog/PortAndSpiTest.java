package com.aipersimmon.ddd.operationlog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aipersimmon.ddd.operationlog.model.Outcome;
import com.aipersimmon.ddd.operationlog.port.AppendResult;
import com.aipersimmon.ddd.operationlog.port.OperationLogCriteria;
import com.aipersimmon.ddd.operationlog.port.OperationLogCursor;
import com.aipersimmon.ddd.operationlog.port.OperationLogPage;
import com.aipersimmon.ddd.operationlog.port.RecordResult;
import com.aipersimmon.ddd.operationlog.spi.ClassifiedOutcome;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PortAndSpiTest {

  @Test
  void record_result_variants_carry_ids() {
    assertEquals("r1", ((RecordResult.Appended) new RecordResult.Appended("r1")).recordId());
    assertEquals(
        "r2", ((RecordResult.Duplicate) new RecordResult.Duplicate("r2")).existingRecordId());
    assertInstanceOf(RecordResult.class, new RecordResult.Skipped("no change"));
    assertInstanceOf(AppendResult.class, new AppendResult.Appended("r3"));
  }

  @Test
  void classified_outcome_factories() {
    ClassifiedOutcome rejected =
        ClassifiedOutcome.rejected("order.closed", "ORDER_STATE", "closed");
    assertEquals(Outcome.REJECTED, rejected.outcome());
    assertEquals("order.closed", rejected.failure().code());
    assertEquals(Outcome.FAILED, ClassifiedOutcome.failed("x", "TECH", "boom").outcome());
    assertThrows(NullPointerException.class, () -> new ClassifiedOutcome(null, null));
  }

  @Test
  void criteria_requires_tenant_and_page_freezes_items() {
    assertThrows(
        NullPointerException.class,
        () -> OperationLogCriteria.forTarget(null, "Order", "o1", Instant.EPOCH, Instant.MAX, 20));
    OperationLogCursor cursor = OperationLogCursor.start();
    assertEquals(null, cursor.token());
    OperationLogPage page = new OperationLogPage(null, null);
    assertThrows(UnsupportedOperationException.class, () -> page.items().add(null));
    assertEquals(List.of(), page.items());
  }
}

package com.example.samples.s01;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.samples.s01.ordering.application.ConfirmOrder;
import com.example.samples.s01.ordering.application.PlaceOrder;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What each of the two capture paths can record, and where the difference comes from.
 *
 * <p>The two commands here are not two styles of the same thing. One has its target's identity in the
 * input and one mints it in the handler, and that single fact decides which path is available, whether a
 * failure can be audited at all, and what the row can say.
 */
class OperationLogCaptureTest extends AuditTestBase {

  @Test
  void thedefinitionPathTakesTheTargetIdFromTheResult() {
    String orderId = commandBus.send(new PlaceOrder("customer-1", lines()));

    Map<String, Object> row = onlyAuditRow();
    assertThat(row.get("operation_code")).isEqualTo("ordering.order.place");
    assertThat(row.get("target_type")).isEqualTo("Order");
    // The id the handler minted. No template over `input` could have produced this.
    assertThat(row.get("target_id")).isEqualTo(orderId);
    assertThat(row.get("outcome")).isEqualTo("SUCCEEDED");
    assertThat(row.get("completion")).isEqualTo("COMMITTED");
    assertThat((String) row.get("summary")).contains(orderId).contains("customer-1");
  }

  @Test
  void theannotationPathTakesItFromTheInput() {
    String orderId = commandBus.send(new PlaceOrder("customer-1", lines()));
    commandBus.send(new ConfirmOrder(orderId));

    Map<String, Object> confirmRow = auditRows().get(1);
    assertThat(confirmRow.get("operation_code")).isEqualTo("ordering.order.confirm");
    assertThat(confirmRow.get("target_id")).isEqualTo(orderId);
    assertThat(confirmRow.get("summary")).isEqualTo("Confirmed order " + orderId);
    // Both rows point at the same target, which is the property that makes the target index worth
    // having: "everything that ever happened to this order" is one indexed read.
    assertThat(auditRows()).extracting(row -> row.get("target_id")).containsExactly(orderId, orderId);
  }

  /**
   * The count, not the payload.
   *
   * <p>A full request snapshot is the default that gets chosen by accident, and it costs three things: the
   * audit table becomes the biggest in the schema, whatever the request happened to contain falls under a
   * retention policy written for audit data, and the log starts answering a question that belongs to the
   * request log. What an audit row owes an auditor is which operation, on what, by whom, with what outcome.
   */
  @Test
  void thelineCountIsRecordedButNotTheLines() {
    commandBus.send(new PlaceOrder("customer-1", lines()));

    Map<String, Object> row = onlyAuditRow();
    assertThat((String) row.get("details")).contains("lineCount").contains("2");
    assertThat((String) row.get("summary")).contains("2 line(s)");
    assertThat((String) row.get("summary")).doesNotContain("SKU-1");
    assertThat((String) row.get("details")).doesNotContain("SKU-1");
  }

  /** Every row carries the causal ids, so an audit entry can be joined to the request that caused it. */
  @Test
  void therowCarriesTheCausalIdsAndTheSource() {
    commandBus.send(new PlaceOrder("customer-1", lines()));

    Map<String, Object> row = onlyAuditRow();
    assertThat(row.get("source")).isEqualTo("s01-http-command-query");
    assertThat(row.get("tenant_id")).isEqualTo("__root__");
    assertThat((String) row.get("message_id")).isNotBlank();
    assertThat((String) row.get("correlation_id")).isNotBlank();
    assertThat((String) row.get("idempotency_key")).hasSize(64);
    assertThat(row.get("schema_version")).isEqualTo(1);
  }

  private static List<PlaceOrder.Line> lines() {
    return List.of(new PlaceOrder.Line("SKU-1", 2), new PlaceOrder.Line("SKU-2", 1));
  }
}

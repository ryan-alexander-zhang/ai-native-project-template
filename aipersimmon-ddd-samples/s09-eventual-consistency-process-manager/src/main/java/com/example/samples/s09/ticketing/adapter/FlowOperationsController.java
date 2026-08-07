package com.example.samples.s09.ticketing.adapter;

import com.aipersimmon.ddd.processmanager.engine.operation.ProcessOperations;
import com.aipersimmon.ddd.processmanager.model.ProcessBusinessKey;
import com.aipersimmon.ddd.processmanager.model.ProcessRef;
import com.aipersimmon.ddd.processmanager.runtime.ProcessQuery;
import com.aipersimmon.ddd.processmanager.runtime.ProcessView;
import com.example.samples.s09.ticketing.application.fulfilment.TicketingDefinition;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The operator's surface, and the answer to "what do you do when a flow is stuck".
 *
 * <p>A durable coordinator's real advantage over a hand-rolled chain of listeners shows up here: the state
 * of every in-flight order is a row, so "where is order 42" is a query rather than an archaeology
 * expedition through logs. The read exposes the two fields that matter when something has gone wrong —
 * the lifecycle (including {@code SUSPENDED}, which the engine sets itself when a step exhausts its
 * retries) and the suspension reason.
 *
 * <p>The write is deliberately narrow. The library offers exactly three operator actions — redrive an
 * effect, redrive a deadline, cancel the instance — and no {@code setState} or {@code forceStep}, because
 * a coordinator whose state can be edited by hand is one whose invariants are whatever the last operator
 * believed. Cancelling takes the expected revision, so two operators acting on a stale view cannot both
 * succeed.
 *
 * <p>In a real deployment this is behind an admin role and an audit trail; the {@code operator} field
 * below is the sample's stand-in for the identity the library records.
 */
@RestController
@RequestMapping("/flows")
class FlowOperationsController {

  private final ProcessQuery query;
  private final ProcessOperations operations;

  FlowOperationsController(ProcessQuery query, ProcessOperations operations) {
    this.query = query;
    this.operations = operations;
  }

  @GetMapping("/{orderId}")
  ResponseEntity<Map<String, Object>> flow(@PathVariable String orderId) {
    return refOf(orderId)
        .flatMap(query::find)
        .map(FlowOperationsController::body)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  /**
   * Stop coordinating this order — the last resort, and note what it does <em>not</em> do: it does not
   * compensate. The seat stays held and the money stays moved, which is why the reason is mandatory and
   * why an operator reaching for this needs to know what the flow had already done. {@code GET} above is
   * how they find out.
   */
  @PostMapping("/{orderId}/cancellation")
  ResponseEntity<Map<String, Object>> cancel(
      @PathVariable String orderId, @Valid @RequestBody CancelFlowRequest request) {
    return refOf(orderId)
        .flatMap(query::find)
        .map(
            view -> {
              operations.cancelProcess(
                  view.processRef(),
                  view.revision().value(),
                  request.operator(),
                  request.reason());
              return ResponseEntity.ok(Map.<String, Object>of("cancelled", orderId));
            })
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  private java.util.Optional<ProcessRef> refOf(String orderId) {
    return query.findRef(TicketingDefinition.PROCESS_TYPE, new ProcessBusinessKey(orderId));
  }

  private static Map<String, Object> body(ProcessView view) {
    Map<String, Object> body = new HashMap<>();
    body.put("processType", view.processRef().processType().value());
    body.put("businessKey", view.processRef().businessKey().value());
    body.put("lifecycle", view.lifecycle().name());
    body.put("step", view.step().value());
    body.put("revision", view.revision().value());
    body.put("outcome", view.outcome().map(outcome -> outcome.value()).orElse(null));
    body.put("suspensionReason", view.suspensionReason().orElse(null));
    return body;
  }

  record CancelFlowRequest(@NotBlank String operator, @NotBlank String reason) {}
}

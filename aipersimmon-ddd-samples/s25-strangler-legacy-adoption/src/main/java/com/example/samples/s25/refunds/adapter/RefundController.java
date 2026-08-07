package com.example.samples.s25.refunds.adapter;

import com.aipersimmon.ddd.cqrs.QueryBus;
import com.example.samples.s25.acl.LegacyRefundEntryPoint;
import com.example.samples.s25.refunds.application.RefundQuery;
import com.example.samples.s25.refunds.application.RefundView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The HTTP edge, and it goes through the strangler seam rather than round it.
 *
 * <p>Which is the detail most likely to be got wrong. A new endpoint that sends {@code RaiseRefund} directly would work,
 * would be one line shorter, and would create a <strong>second</strong> path into the table — so the route switch would
 * no longer describe the system, and reverting to {@code LEGACY_ONLY} would leave this endpoint writing through the new
 * context anyway. During a migration, "how many ways in are there" has to stay answerable, and the answer has to be one.
 *
 * <p>The identity in the URL is the legacy number, because that is what the monolith's callers have. The identity in the
 * <em>response</em> leads with {@code publicId}, so a new consumer has something durable to hold — see
 * {@code refunds.api.RefundRaised}.
 */
@RestController
class RefundController {

  private final LegacyRefundEntryPoint refunds;
  private final QueryBus queries;

  RefundController(LegacyRefundEntryPoint refunds, QueryBus queries) {
    this.refunds = refunds;
    this.queries = queries;
  }

  record RaiseRequest(@Min(1) long orderId, @Min(1) long amountCents, String reason) {}

  record ApproveRequest(@NotBlank String approvedBy) {}

  @PostMapping("/refunds")
  ResponseEntity<RefundView> raise(@Valid @RequestBody RaiseRequest request) {
    long id = refunds.raiseRefund(request.orderId(), request.amountCents(), request.reason());
    return ResponseEntity.created(URI.create("/refunds/" + id))
        .body(queries.ask(new RefundQuery(id)));
  }

  @PostMapping("/refunds/{id}/approval")
  ResponseEntity<RefundView> approve(
      @PathVariable long id, @Valid @RequestBody ApproveRequest request) {
    refunds.approveRefund(id, request.approvedBy());
    return ResponseEntity.ok(queries.ask(new RefundQuery(id)));
  }

  @GetMapping("/refunds/{id}")
  RefundView read(@PathVariable long id) {
    return queries.ask(new RefundQuery(id));
  }
}

package com.example.samples.s06.risk.adapter;

import com.aipersimmon.ddd.cqrs.QueryBus;
import com.example.samples.s06.risk.application.AssessRisk;
import com.example.samples.s06.risk.domain.RiskDecision;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The callee's whole API: one endpoint, and two kinds of "no".
 *
 * <p><strong>A POST that changes nothing.</strong> The input is a small object rather than a couple of
 * path segments, and putting a customer id and an amount in a URL puts them in every access log and proxy
 * cache between here and the caller. So: POST, and a comment saying why, because a POST that is not a
 * mutation is worth one sentence of explanation forever.
 *
 * <p><strong>200 with {@code approved: false} for a rejection; 4xx only for a bad request.</strong> The
 * two must not be the same status. A caller needs to distinguish "the answer is no, tell the customer"
 * from "your payload was wrong, fix the code" — and if both arrive as 422 it cannot. The framework's web
 * layer produces the RFC 9457 problem body for the second case, so this controller writes no error
 * handling at all: {@code @Valid} failing is already a problem response with a machine-readable code.
 */
@RestController
@RequestMapping("/risk-assessments")
class RiskController {

  private final QueryBus queryBus;

  RiskController(QueryBus queryBus) {
    this.queryBus = queryBus;
  }

  @PostMapping
  ResponseEntity<Map<String, Object>> assess(@Valid @RequestBody AssessmentRequest request) {
    RiskDecision decision =
        queryBus.ask(new AssessRisk(request.customerId(), request.amountCents()));
    Map<String, Object> body =
        decision.approved()
            ? Map.of("approved", true)
            : Map.of("approved", false, "reason", decision.reason());
    return ResponseEntity.ok(body);
  }

  record AssessmentRequest(@NotBlank String customerId, @Positive long amountCents) {}
}

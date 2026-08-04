package com.example.samples.s10.points.web;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.tenancy.TenantContext;
import com.example.samples.s10.points.application.AwardPoints;
import com.example.samples.s10.points.application.ReservePoints;
import com.example.samples.s10.points.application.SettlePointsReservation;
import com.example.samples.s10.points.domain.AwardOutcome;
import com.example.samples.s10.points.domain.ReserveOutcome;
import com.example.samples.s10.points.domain.SettleOutcome;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.apache.seata.core.context.RootContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The participant's edge, and the only file in this service that knows Seata exists.
 *
 * <p>It knows for exactly one reason: <strong>each endpoint must refuse to run under the wrong protocol,
 * loudly.</strong>
 *
 * <ul>
 *   <li>{@code POST /awards} is the AT participant. Without an XID its write would commit locally and
 *       permanently, so a dropped {@code TX_XID} header would silently convert "both or neither" into
 *       "always the first half". A dropped header is a configuration mistake, and a configuration mistake
 *       that corrupts data quietly is the worst kind — so this refuses instead.
 *   <li>{@code POST /reservations} and its settlements are the TCC participant, and they refuse the
 *       opposite way: <em>with</em> an XID they would also register an AT branch, so the same write would
 *       be undone twice by two protocols that disagree about how. TCC's phases are deliberately outside
 *       the global transaction; the caller registers the branch, not the participant.
 * </ul>
 *
 * <p>That asymmetry — AT propagates the XID, TCC must not — is the single easiest thing to get wrong when
 * both live in one system, and it is invisible until the day something rolls back.
 */
@RestController
class PointsController {

  private final CommandBus commandBus;
  private final JdbcTemplate jdbc;

  PointsController(CommandBus commandBus, JdbcTemplate jdbc) {
    this.commandBus = commandBus;
    this.jdbc = jdbc;
  }

  /** AT participant: joins the caller's global transaction as a branch. */
  @PostMapping("/awards")
  ResponseEntity<OutcomeResponse> award(@Valid @RequestBody AwardRequest request) {
    if (!RootContext.inGlobalTransaction()) {
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .body(
              new OutcomeResponse(
                  "NO_GLOBAL_TRANSACTION",
                  "this endpoint is an AT participant and will not write outside a global transaction;"
                      + " the caller did not propagate TX_XID"));
    }
    AwardOutcome outcome =
        commandBus.send(
            new AwardPoints(request.reference(), request.accountId(), request.points()));
    return ResponseEntity.ok(new OutcomeResponse(outcome.name(), RootContext.getXID()));
  }

  /** TCC Try. */
  @PostMapping("/reservations")
  ResponseEntity<OutcomeResponse> reserve(@Valid @RequestBody AwardRequest request) {
    ResponseEntity<OutcomeResponse> refusal = refuseIfInGlobalTransaction();
    if (refusal != null) {
      return refusal;
    }
    ReserveOutcome outcome =
        commandBus.send(
            new ReservePoints(request.reference(), request.accountId(), request.points()));
    HttpStatus status =
        outcome == ReserveOutcome.CANCELLED_BEFORE_RESERVED ? HttpStatus.CONFLICT : HttpStatus.OK;
    return ResponseEntity.status(status).body(new OutcomeResponse(outcome.name(), null));
  }

  /** TCC Confirm. */
  @PostMapping("/reservations/{reference}/confirm")
  ResponseEntity<OutcomeResponse> confirm(
      @PathVariable String reference, @Valid @RequestBody SettleRequest request) {
    return settle(reference, request, SettlePointsReservation.Direction.CONFIRM);
  }

  /** TCC Cancel. */
  @PostMapping("/reservations/{reference}/cancel")
  ResponseEntity<OutcomeResponse> cancel(
      @PathVariable String reference, @Valid @RequestBody SettleRequest request) {
    return settle(reference, request, SettlePointsReservation.Direction.CANCEL);
  }

  private ResponseEntity<OutcomeResponse> settle(
      String reference, SettleRequest request, SettlePointsReservation.Direction direction) {
    ResponseEntity<OutcomeResponse> refusal = refuseIfInGlobalTransaction();
    if (refusal != null) {
      return refusal;
    }
    SettleOutcome outcome =
        commandBus.send(
            new SettlePointsReservation(reference, request.accountId(), request.points(), direction));
    return ResponseEntity.ok(new OutcomeResponse(outcome.name(), null));
  }

  private ResponseEntity<OutcomeResponse> refuseIfInGlobalTransaction() {
    if (!RootContext.inGlobalTransaction()) {
      return null;
    }
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new OutcomeResponse(
                "UNEXPECTED_GLOBAL_TRANSACTION",
                "this is a TCC phase and must run in its own local transaction; the caller propagated"
                    + " TX_XID "
                    + RootContext.getXID()
                    + ", which would register an AT branch for the same write"));
  }

  /**
   * The read side, so a test or an operator can see both numbers without a database client.
   *
   * <p>The tenant predicate is written by hand here, and it has to be: the tenant-line interceptor is a
   * MyBatis interceptor, and this is a {@code JdbcTemplate}. Every hand-rolled query is outside that
   * safety net — which is worth knowing before someone adds a "quick reporting endpoint".
   */
  @GetMapping("/points/{accountId}")
  PointsView view(@PathVariable String accountId) {
    return jdbc.queryForObject(
        "SELECT awarded, frozen FROM s10_points_account WHERE tenant_id = ? AND account_id = ?",
        (rs, row) -> new PointsView(accountId, rs.getInt("awarded"), rs.getInt("frozen")),
        TenantContext.effective().value(),
        accountId);
  }

  record AwardRequest(
      @NotBlank String reference, @NotBlank String accountId, @Positive int points) {}

  record SettleRequest(@NotBlank String accountId, int points) {}

  record OutcomeResponse(String outcome, String detail) {}

  record PointsView(String accountId, int awarded, int frozen) {}
}

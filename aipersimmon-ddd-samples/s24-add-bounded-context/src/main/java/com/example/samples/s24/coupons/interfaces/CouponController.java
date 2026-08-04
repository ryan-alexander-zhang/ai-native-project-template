package com.example.samples.s24.coupons.interfaces;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.example.samples.s24.coupons.application.IssueCoupon;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The new context's own edge, and the fact that it has one is part of the answer to "when should it become its own
 * deployment unit".
 *
 * <p>A context that only ever answers other contexts has no outside; this one is issued to by an operator, so it needs
 * a door. Which means the split is not blocked by the edge — the door already exists and would simply move. The things
 * that <em>would</em> block it are elsewhere: a cycle, a shared table, a transaction that spans the boundary.
 *
 * <p>Note what is not here: no endpoint to redeem a coupon and none to quote one. Redemption is a consequence of
 * placing an order, not something a client does; quoting happens inside pricing. Exposing either would create a second
 * way to reach the same rule, and the second way is the one that skips a step.
 */
@RestController
class CouponController {

  private final CommandBus commands;

  CouponController(CommandBus commands) {
    this.commands = commands;
  }

  record IssueRequest(
      Integer percentOff,
      Long amountOffMinor,
      @NotBlank String currency,
      Instant validFrom,
      Instant validUntil,
      @Min(1) int maxRedemptions) {}

  @PutMapping("/coupons/{code}")
  ResponseEntity<Void> issue(@PathVariable String code, @Valid @RequestBody IssueRequest request) {
    commands.send(
        new IssueCoupon(
            code,
            request.percentOff(),
            request.amountOffMinor(),
            request.currency(),
            request.validFrom(),
            request.validUntil(),
            request.maxRedemptions()));
    return ResponseEntity.created(URI.create("/coupons/" + code)).build();
  }
}

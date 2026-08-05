package com.example.samples.s25.acl;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.example.samples.s25.legacy.LegacyOrderService;
import com.example.samples.s25.refunds.application.ApproveRefund;
import com.example.samples.s25.refunds.application.RaiseRefund;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The strangler's seam: the legacy signature, routed by a switch.
 *
 * <p>Every existing caller of {@code LegacyOrderService.raiseRefund(orderId, amount, reason)} calls this instead, with
 * the same arguments and the same return type. Which is the property that makes a strangler a strangler rather than a
 * rewrite: <strong>the callers do not change, and the route is a config value.</strong>
 *
 * <p>It lives in {@code acl} rather than in {@code legacy} for a reason worth stating: it depends on the new context,
 * and the rule over the {@code legacy} package is that the monolith may not. A delegating shim inside the monolith would
 * be the first thread of the new code growing back into the old, and the second one would not be reviewed.
 *
 * <h2>The three routes, and why the middle one is where you want to live</h2>
 *
 * <ul>
 *   <li>{@code LEGACY_ONLY} — as inherited. Kept reachable so the switch is revertible by configuration rather than by a
 *       rollback, which is the difference between a bad afternoon and a bad week;
 *   <li>{@code NEW_WRITES} — the default here, and the state to reach quickly. The new context owns every write; the
 *       legacy code still reads. <strong>This is the only route in which the version column means anything</strong>, and
 *       {@code VersionColumnTest} measures why: with two writers, the optimistic lock cannot see the one that does not
 *       participate;
 *   <li>{@code NEW_ONLY} — the legacy entry point is gone. A conclusion, not a setting: {@code DoneCriterionTest}
 *       computes whether it has been reached.
 * </ul>
 *
 * <p>There is deliberately <strong>no {@code BOTH} route</strong>. Writing to the table twice — once through each path —
 * is the arrangement everybody reaches for during a migration, and {@code DoubleWriteTest} measures what it costs: no
 * mechanism in the library can reconcile two writers of one row, because the second one is invisible to the first.
 * One writer, two readers.
 */
@Component
public class LegacyRefundEntryPoint {

  /** Which path serves refunds. See the class javadoc; there is no {@code BOTH}. */
  public enum Route {
    LEGACY_ONLY,
    NEW_WRITES,
    NEW_ONLY
  }

  private final LegacyOrderService legacy;
  private final CommandBus commands;
  private final Route route;

  LegacyRefundEntryPoint(
      LegacyOrderService legacy,
      CommandBus commands,
      @Value("${s25.refunds.route:NEW_WRITES}") Route route) {
    this.legacy = legacy;
    this.commands = commands;
    this.route = route;
  }

  public Route route() {
    return route;
  }

  /** The legacy signature, unchanged: same arguments, same {@code long} return. */
  public long raiseRefund(long orderId, long amountCents, String reason) {
    if (route == Route.LEGACY_ONLY) {
      return legacy.raiseRefund(orderId, amountCents, reason);
    }
    return commands.send(new RaiseRefund(orderId, amountCents, reason));
  }

  /**
   * The legacy signature, unchanged — and the one place a behaviour change is visible.
   *
   * <p>The old method returned {@code void} and swallowed "already approved". The new one refuses. Callers that relied on
   * the silence will now see a conflict, which is an improvement and is still a change: it is called out here, in
   * {@code ApproveRefund}, and asserted in {@code StranglerTest}, because a behaviour change nobody wrote down is
   * indistinguishable from a regression.
   */
  public void approveRefund(long refundId, String approvedBy) {
    if (route == Route.LEGACY_ONLY) {
      legacy.approveRefund(refundId, approvedBy);
      return;
    }
    commands.send(new ApproveRefund(refundId, approvedBy));
  }
}

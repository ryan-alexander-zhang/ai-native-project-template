package com.example.samples.s11.ordering.adapter;

import com.example.samples.s11.ordering.application.ExpiredOrderSweep;
import com.example.samples.s11.ordering.application.SweepReport;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The operator's entry: run one round now, and tell me what it did.
 *
 * <p>The fourth entry shape, and the one most systems grow by accident — as a script somebody keeps in
 * a home directory, or an {@code UPDATE} pasted into a database console at 2am. Making it a real entry
 * costs one method, because the work was already a callable unit rather than something welded to a
 * timer. It goes through the same commands, obeys the same rules, emits the same events, and its
 * outcome is the same report the schedule logs.
 *
 * <p>What it deliberately does not do is take parameters. "Close everything overdue before this date"
 * is a different, unbounded operation wearing the same name, and an operator entry that accepts a
 * predicate is the bulk statement again with extra steps. Anything genuinely one-off is a data fix, and
 * a data fix should look like one.
 *
 * <p>In a real deployment this sits behind the same authorization as the rest of the operations
 * surface; this sample has no security tier, and pretending otherwise with a fake role check would
 * teach the wrong thing.
 */
@RestController
@RequestMapping("/operations")
class OperationsController {

  private final ExpiredOrderSweep sweep;

  OperationsController(ExpiredOrderSweep sweep) {
    this.sweep = sweep;
  }

  @PostMapping("/expired-order-sweep")
  SweepReport sweepNow() {
    return sweep.sweepOnce();
  }
}

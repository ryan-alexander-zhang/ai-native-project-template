package com.example.samples.s28.reconciliation.application;

import com.example.samples.s28.reconciliation.domain.ExportJobId;
import java.util.Optional;

/**
 * Where a running export says how far it has got.
 *
 * <p>Not a repository, not an aggregate, no version column, and no history — one row per job, overwritten.
 * The argument is in {@code ExportJob}: nothing is decided by progress, so nothing needs to be protected
 * about it, and putting it behind the aggregate's optimistic lock would only make it collide with the write
 * that matters.
 *
 * <p>The subtle part is not the table, it is the <em>transaction</em>. A tick written inside the export's own
 * read transaction is invisible to everybody until that transaction commits, which for a long export is
 * exactly when progress stops being interesting. So the default implementation commits each tick on its own
 * connection. Two consequences a deployment has to know about:
 *
 * <ul>
 *   <li>each in-flight export holds <strong>two</strong> pool connections while it ticks, not one;
 *   <li>progress survives a rollback of the export, which is correct — the rows really were read — and means
 *       a failed job's last known position stays readable.
 * </ul>
 */
public interface ProgressBoard {

  /**
   * Publish where the export has got to.
   *
   * @param total the expected row count if it is known cheaply, else null — a progress reading of "41,000
   *     rows" with no denominator is still useful, and a {@code COUNT(*)} over the period to obtain one is
   *     not free
   */
  void report(ExportJobId id, long rowsDone, Long total);

  Optional<ExportProgress> of(ExportJobId id);

  /** Drop the row when a job is re-queued, so a retry does not start out claiming last attempt's position. */
  void forget(ExportJobId id);
}

package com.example.samples.s11.ordering.application;

import java.util.List;

/**
 * What one round of the sweep did. A batch entry has no caller waiting on an HTTP response, so if it
 * does not produce a result somebody can read, nothing about it is observable — the most common shape
 * of a broken scheduled job is one that has been failing quietly for weeks.
 *
 * <p>The three outcomes are deliberately distinct:
 *
 * <ul>
 *   <li><strong>closed</strong> — the command succeeded and committed.
 *   <li><strong>skipped</strong> — the aggregate refused, because the world moved between the scan
 *       and the command (paid, already closed, gone). This is <em>not</em> an error: it is the
 *       correct outcome of an advisory scan, and a job that logs it as a failure trains its operators
 *       to ignore failures.
 *   <li><strong>failures</strong> — something actually went wrong. These are the ones worth an alert,
 *       and they carry the id so the next round can be reasoned about.
 * </ul>
 *
 * @param runId the correlation id shared by every command of this round — one log query finds them
 * @param scanned how many candidates the scan proposed
 */
public record SweepReport(
    String runId, int scanned, int closed, int skipped, List<Failure> failures) {

  public SweepReport {
    failures = failures == null ? List.of() : List.copyOf(failures);
  }

  /** One order the round could not finish, and why. */
  public record Failure(String orderId, String reason) {}

  public boolean allSucceeded() {
    return failures.isEmpty();
  }
}

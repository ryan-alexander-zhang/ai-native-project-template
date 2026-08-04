package com.example.samples.s28.reconciliation.application;

import java.io.PrintWriter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The synchronous export, kept in main code because it is the shape every service ships first and because the
 * scenario's first question is where its limit is.
 *
 * <p>It is not a strawman. For a small period it is better than the job: one request, one answer, no queue, no
 * polling, nothing to operate. The trouble is that nothing about it says when it stops being better, and the
 * limit is nowhere near where people look for it:
 *
 * <ul>
 *   <li><strong>Not the HTTP timeouts.</strong> A load balancer's idle timeout is typically 60 seconds and the
 *       client's read timeout is whatever the client set, so those are the numbers that get quoted. They are
 *       the last constraints to bite, and the first symptom is not a timeout at all.
 *   <li><strong>The connection pool.</strong> This method holds one pooled connection for its whole duration.
 *       Ten concurrent callers on a pool of ten is a service where <em>every other endpoint</em> starts failing
 *       to get a connection — including the health check. {@code SynchronousLimitTest} measures exactly that,
 *       with the pool turned down to 2, and it is the number worth knowing: the limit is set by concurrency
 *       times duration against the pool, not by anybody's patience.
 *   <li><strong>Nothing in the library caps it.</strong> There is no timeout on a command, a query or a
 *       handler; a handler that takes four minutes takes four minutes. The same test asserts that, because
 *       "surely something would stop it" is the assumption worth removing.
 * </ul>
 *
 * <p>And two things it cannot do at any size: report progress (there is nowhere to report it to — the caller is
 * blocked on the response), and be retried safely (a client that times out at three minutes cannot tell whether
 * the work happened, so it retries, and now two exports are running).
 */
@Component
public class InlineExport {

  private final ExportSource source;
  private final TransactionTemplate readOnly;

  InlineExport(ExportSource source, PlatformTransactionManager transactions) {
    this.source = source;
    this.readOnly = new TransactionTemplate(transactions);
    this.readOnly.setReadOnly(true);
  }

  /**
   * Stream the period straight to the caller.
   *
   * <p>Streaming rather than buffering, so this is the <em>good</em> version of the bad idea: it holds one row
   * at a time, not a million. Which is the point worth being precise about — streaming fixes the memory problem
   * and leaves the transaction-duration problem exactly where it was, and the second one is the one that takes
   * the service down.
   */
  public long writeTo(String period, PrintWriter out) {
    long[] rows = {0};
    out.println("id,order_ref,amount_cents,note");
    readOnly.executeWithoutResult(
        status ->
            source.streamPeriod(
                period,
                row -> {
                  out.println(
                      row.id() + "," + row.orderRef() + "," + row.amountCents() + "," + row.note());
                  rows[0]++;
                }));
    return rows[0];
  }
}

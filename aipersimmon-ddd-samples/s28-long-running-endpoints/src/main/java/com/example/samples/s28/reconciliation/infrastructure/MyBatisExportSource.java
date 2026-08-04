package com.example.samples.s28.reconciliation.infrastructure;

import com.example.samples.s28.reconciliation.application.ExportRowView;
import com.example.samples.s28.reconciliation.application.ExportSource;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The source reads, plus a guard that turns a silent bug into a loud one.
 *
 * <p>{@link #streamPeriod} refuses to run outside a transaction. Not because MyBatis would fail — it would not,
 * and that is exactly the problem: without a transaction the PostgreSQL driver ignores the fetch size, reads
 * every row of the period into memory, and then hands them to the handler one at a time. The code looks like
 * streaming at every level, the test passes on a small period, and the first real month is an
 * {@code OutOfMemoryError} in production.
 *
 * <p>The library makes the same move for the same reason — {@code saveAggregate} calls
 * {@code requireActiveTransaction} rather than trusting the caller — and the argument generalises: a
 * precondition that cannot be detected from the result has to be checked before the work, or it is not checked
 * at all.
 */
@Component
class MyBatisExportSource implements ExportSource {

  private final ExportSourceMapper mapper;

  MyBatisExportSource(ExportSourceMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public void streamPeriod(String period, Consumer<ExportRowView> consumer) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "streamPeriod was called outside a transaction. It would still have returned every row —"
              + " and it would have read all of them into memory first, because PostgreSQL only opens a"
              + " server-side cursor on a connection that is not in autocommit. Wrap the read in a"
              + " transaction, or use the CHUNKED read mode, which needs none.");
    }
    mapper.streamPeriod(period, context -> consumer.accept(context.getResultObject()));
  }

  @Override
  public List<ExportRowView> pageAfter(String period, long afterId, int limit) {
    return mapper.pageAfter(period, afterId, limit);
  }

  @Override
  public long countPeriod(String period) {
    return mapper.countPeriod(period);
  }
}

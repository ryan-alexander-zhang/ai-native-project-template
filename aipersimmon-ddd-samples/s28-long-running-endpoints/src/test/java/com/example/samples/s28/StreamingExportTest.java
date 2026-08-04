package com.example.samples.s28;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.samples.s28.reconciliation.application.ExportRowView;
import com.example.samples.s28.reconciliation.application.ExportSettings;
import com.example.samples.s28.reconciliation.application.ExportRunner;
import com.example.samples.s28.reconciliation.application.ExportSource;
import com.example.samples.s28.reconciliation.domain.ExportJobId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * How a million rows leave the database, measured rather than described.
 *
 * <p>Two questions, and the first one is the trap: <em>is this actually streaming?</em> Every layer says it is —
 * a {@code ResultHandler} instead of a list, a fetch size on the statement — and the layer that decides is the JDBC
 * driver, which will read the entire result set into memory without complaining if either of its two conditions is
 * missing. Nothing fails, nothing warns, and the query still returns every row.
 *
 * <p>So it is measured the only way that is deterministic: with a query that <strong>fails on its last row</strong>.
 * If the server streamed, the client has already seen the earlier rows when the failure arrives. If the driver
 * buffered, it has seen none of them. No timing, no heap inspection, no flakiness — one integer that can only have
 * come from one of the two behaviours.
 */
class StreamingExportTest extends ReconciliationTestBase {

  @Autowired private DataSource dataSource;
  @Autowired private ExportSource source;
  @Autowired private BufferedExport buffered;
  @Autowired private PlatformTransactionManager transactions;

  private static final int ROWS = 5_000;

  @AfterEach
  void restoreTheReadMode() {
    settings.setReadMode(ExportSettings.ReadMode.SNAPSHOT);
  }

  /**
   * Both conditions are required, and missing either is silent.
   *
   * <p>The numbers are exact and worth reading. In the streaming case the count is a whole multiple of the fetch
   * size rather than {@code ROWS - 1}: the driver hands over complete batches, so the last batch — the one holding
   * the row that fails — never arrives. That multiple is itself the proof that the fetch size was honoured.
   */
  @Test
  void acursorNeedsBothATransactionAndAFetchSize() throws SQLException {
    seed(PERIOD, ROWS);
    long lastId = jdbc.queryForObject("SELECT MAX(id) FROM s28_export_row", Long.class);
    // Divides by zero on the last row only, and no ORDER BY, so PostgreSQL produces rows incrementally.
    String boobyTrapped =
        "SELECT id, 1 / (CASE WHEN id = " + lastId + " THEN 0 ELSE 1 END) AS ok"
            + " FROM s28_export_row WHERE period = '" + PERIOD + "'";

    int streaming = rowsSeenBeforeFailure(boobyTrapped, false, 500);
    int noTransaction = rowsSeenBeforeFailure(boobyTrapped, true, 500);
    int noFetchSize = rowsSeenBeforeFailure(boobyTrapped, false, 0);

    assertThat(streaming)
        .as("nine whole batches of 500 arrived; the tenth held the row that failed")
        .isEqualTo(4_500);
    assertThat(noTransaction)
        .as("autocommit: the driver ignored the fetch size and read everything first")
        .isZero();
    assertThat(noFetchSize)
        .as("no fetch size: no cursor, so again everything was read first")
        .isZero();
  }

  /**
   * The two annotations the streaming read depends on, pinned — and this test is weaker than the ones around it, on
   * purpose, because the alternative is nothing at all.
   *
   * <p>The behavioural claim is measured above, in raw JDBC, because both conditions belong to the connection and the
   * statement. What that measurement cannot notice is somebody deleting the fetch size <em>from this mapper</em>: the
   * query would still return every row, every other test here would still pass, and the export would quietly start
   * loading a month into heap. So this asserts the configuration rather than the behaviour, which demonstrates
   * nothing — it is a regression guard on a value whose absence has no symptom.
   */
  @Test
  void thestreamingMapperStillCarriesTheAnnotationsItDependsOn() throws Exception {
    var method =
        Class.forName("com.example.samples.s28.reconciliation.infrastructure.ExportSourceMapper")
            .getDeclaredMethod(
                "streamPeriod", String.class, org.apache.ibatis.session.ResultHandler.class);
    assertThat(method.getAnnotation(org.apache.ibatis.annotations.Options.class))
        .as("no fetch size, no cursor — and no failure either")
        .isNotNull()
        .extracting(org.apache.ibatis.annotations.Options::fetchSize)
        .isEqualTo(500);
    assertThat(method.getAnnotation(org.apache.ibatis.annotations.ResultType.class))
        .as("a void method has nothing for MyBatis to infer a row type from")
        .isNotNull();
  }

  /**
   * Which is why the sample's own read refuses to run without a transaction.
   *
   * <p>A precondition whose absence cannot be detected from the result has to be checked before the work — the same
   * move the library makes in {@code saveAggregate}.
   */
  @Test
  void thestreamingReadRefusesToRunWhereItWouldSilentlyBuffer() {
    seed(PERIOD, 10);
    assertThatThrownBy(() -> source.streamPeriod(PERIOD, row -> {}))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("outside a transaction");
  }

  /**
   * One row object at a time, against every row at once. The two numbers, measured.
   *
   * <p>The streaming count is the peak number of row objects the application had reachable simultaneously; the
   * buffered count is the size of the list, which is by definition how many it is holding. This is not a claim about
   * driver buffers — that is the test above — it is the part the application controls, and it is the part a
   * {@code List} return type gives away for free.
   */
  @Test
  void streamingHoldsOneRowAndTheListHoldsAllOfThem() {
    seed(PERIOD, ROWS);
    AtomicInteger live = new AtomicInteger();
    AtomicInteger peak = new AtomicInteger();
    AtomicLong handled = new AtomicLong();

    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status ->
                source.streamPeriod(
                    PERIOD,
                    row -> {
                      peak.accumulateAndGet(live.incrementAndGet(), Math::max);
                      handled.incrementAndGet();
                      live.decrementAndGet();
                    }));

    assertThat(handled).hasValue(ROWS);
    assertThat(peak).as("the handler is given one row and hands it back").hasValue(1);
    assertThat(buffered.everything(PERIOD))
        .as("and the list return type holds every one of them")
        .hasSize(ROWS);
  }

  /**
   * A snapshot read and a keyset-paged read are not the same export, and this measures the difference.
   *
   * <p>A row is inserted after the export has started. Under SNAPSHOT the whole read is one transaction, so the new
   * row is not in the file. Under CHUNKED each page is its own query, so a row that sorts after the cursor is picked
   * up by a later page and is in the file.
   *
   * <p>Neither answer is wrong. "The June file" usually has to mean one snapshot; a nightly extract that must not
   * hold a transaction for an hour usually has to be chunked. What is wrong is not knowing which one you shipped.
   */
  @Test
  void asnapshotReadAndAChunkedReadDisagreeAboutRowsThatArriveMidExport() {
    seed(PERIOD, 1_200);
    settings.setPageSize(500);

    long snapshotRows = countWhileInserting(ExportSettings.ReadMode.SNAPSHOT);
    long chunkedRows = countWhileInserting(ExportSettings.ReadMode.CHUNKED);

    assertThat(snapshotRows).as("one transaction, one picture").isEqualTo(1_200);
    assertThat(chunkedRows).as("the late row sorted after the cursor, so a later page found it")
        .isEqualTo(1_201);
  }

  /** The artifact is a file, and the download reads the file rather than the table. */
  @Test
  void thefinishedExportIsAFileWithAHeaderAndEveryRow() throws Exception {
    seed(PERIOD, 250);
    submit("exp-file");
    ExportJobId claimed = claimAs("worker-a");
    assertThat(runner.run(claimed, "worker-a")).isEqualTo(ExportRunner.Outcome.SUCCEEDED);

    String path = (String) jobRow("exp-file").get("artifact_path");
    assertThat(Path.of(path)).exists();
    assertThat(Files.readAllLines(Path.of(path)))
        .hasSize(251)
        .first()
        .isEqualTo("id,order_ref,amount_cents,note");
    assertThat(jobRow("exp-file").get("artifact_rows")).isEqualTo(250L);
    assertThat((Long) jobRow("exp-file").get("artifact_bytes")).isEqualTo(Files.size(Path.of(path)));
  }

  /** A failed run leaves no {@code .csv} behind — only the draft name, which nothing looks for. */
  @Test
  void afailedRunPublishesNoArtifact() {
    seed(PERIOD, 100);
    submit("exp-doomed");
    ExportJobId claimed = claimAs("worker-a");
    jdbc.execute("ALTER TABLE s28_export_row RENAME TO s28_export_row_hidden");
    try {
      assertThat(runner.run(claimed, "worker-a")).isEqualTo(ExportRunner.Outcome.FAILED);
    } finally {
      jdbc.execute("ALTER TABLE s28_export_row_hidden RENAME TO s28_export_row");
    }
    assertThat(jobRow("exp-doomed").get("artifact_path")).isNull();
    assertThat(jobRow("exp-doomed").get("failure")).asString().isNotEmpty();
    assertThat(Path.of(settings.getArtifactDir(), "exp-doomed.csv")).doesNotExist();
  }

  /** Read the whole period in the given mode, inserting one extra row once the read has begun. */
  private long countWhileInserting(ExportSettings.ReadMode mode) {
    settings.setReadMode(mode);
    AtomicLong seen = new AtomicLong();
    boolean[] inserted = {false};
    switch (mode) {
      case SNAPSHOT ->
          new TransactionTemplate(transactions)
              .executeWithoutResult(
                  status ->
                      source.streamPeriod(
                          PERIOD,
                          row -> {
                            if (!inserted[0]) {
                              insertOneLateRow();
                              inserted[0] = true;
                            }
                            seen.incrementAndGet();
                          }));
      case CHUNKED -> {
        long after = 0;
        while (true) {
          var page = source.pageAfter(PERIOD, after, settings.getPageSize());
          if (page.isEmpty()) {
            break;
          }
          if (!inserted[0]) {
            insertOneLateRow();
            inserted[0] = true;
          }
          for (ExportRowView row : page) {
            seen.incrementAndGet();
            after = row.id();
          }
        }
      }
    }
    jdbc.update("DELETE FROM s28_export_row WHERE order_ref = 'ORD-LATE'");
    return seen.get();
  }

  /**
   * Inserted on a connection of its own and committed at once, so the export cannot see it through its own
   * transaction — which is the whole point of the comparison.
   */
  private void insertOneLateRow() {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(true);
      try (PreparedStatement insert =
          connection.prepareStatement(
              "INSERT INTO s28_export_row (period, order_ref, amount_cents, note)"
                  + " VALUES (?, 'ORD-LATE', 1, 'arrived mid-export')")) {
        insert.setString(1, PERIOD);
        insert.execute();
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Count how many rows arrive before the query fails, under exactly the conditions given.
   *
   * <p>Raw JDBC rather than the mapper, because the two conditions being measured are properties of the connection
   * and the statement, and going through MyBatis would only add a layer that cannot change the answer.
   */
  private int rowsSeenBeforeFailure(String sql, boolean autoCommit, int fetchSize)
      throws SQLException {
    int seen = 0;
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(autoCommit);
      try (PreparedStatement query =
          connection.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
        query.setFetchSize(fetchSize);
        try (ResultSet rows = query.executeQuery()) {
          while (rows.next()) {
            seen++;
          }
        }
        throw new AssertionError("the booby-trapped query was expected to fail on its last row");
      } catch (SQLException expected) {
        if (!autoCommit) {
          connection.rollback();
        }
        return seen;
      }
    }
  }
}

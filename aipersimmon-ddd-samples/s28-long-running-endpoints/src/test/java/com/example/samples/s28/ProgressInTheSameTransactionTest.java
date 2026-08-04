package com.example.samples.s28;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import com.example.samples.s28.reconciliation.application.ProgressBoard;
import com.example.samples.s28.reconciliation.application.SubmitExport;
import com.example.samples.s28.reconciliation.domain.ExportJobId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The counterexample the sample ships, measured — because a failure demonstrated without its control is not a
 * finding.
 *
 * <p>{@code s28.export.progress-transaction=SAME_TRANSACTION} is the shape most implementations of a progress
 * counter end up being: no extra connection, no propagation annotation, nothing that looks wrong. Its own author's
 * test passes, because a transaction can read its own uncommitted writes. Everybody else sees nothing until the work
 * is over.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "s28.worker.enabled=false",
      "s28.export.progress-transaction=SAME_TRANSACTION"
    })
@Import(PostgresServiceConnection.class)
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class ProgressInTheSameTransactionTest {

  @Autowired private CommandBus commandBus;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;
  @Autowired private ProgressBoard progress;
  @Autowired private PlatformTransactionManager transactions;

  @BeforeEach
  void emptyEverything() {
    jdbc.update("DELETE FROM s28_export_progress");
    jdbc.update("DELETE FROM s28_export_job");
  }

  /** The bean the property selected really is the joining one. */
  @Test
  void thesameTransactionBoardIsTheOneInPlay() {
    assertThat(progress.getClass().getName()).contains("SameTransaction");
  }

  /**
   * Invisible from anywhere else until commit, visible to itself throughout. The two readings that make this mistake
   * survive review.
   */
  @Test
  void atickIsInvisibleUntilTheWorkIsOverAndTheWriterCannotTell() {
    commandBus.send(new SubmitExport("exp-hidden", "2026-06"));
    ExportJobId id = new ExportJobId("exp-hidden");

    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              progress.report(id, 4_100, 90_000L);
              assertThat(progress.of(id))
                  .as("the writer's own transaction can see it, which is why this passes review")
                  .get()
                  .extracting("rowsDone")
                  .isEqualTo(4_100L);
              assertThat(rowsDoneOnAnotherConnection("exp-hidden"))
                  .as("and nobody polling the job can")
                  .isEqualTo(-1L);
            });

    assertThat(rowsDoneOnAnotherConnection("exp-hidden"))
        .as("it arrives on commit — by which time progress is history")
        .isEqualTo(4_100L);
  }

  private long rowsDoneOnAnotherConnection(String jobId) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(true);
      try (PreparedStatement query =
          connection.prepareStatement(
              "SELECT rows_done FROM s28_export_progress WHERE job_id = ?")) {
        query.setString(1, jobId);
        try (ResultSet rows = query.executeQuery()) {
          return rows.next() ? rows.getLong(1) : -1;
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}

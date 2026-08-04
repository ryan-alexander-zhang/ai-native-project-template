package com.example.samples.s28;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import com.example.samples.s28.reconciliation.application.ExportClaims;
import com.example.samples.s28.reconciliation.application.ExportRunner;
import com.example.samples.s28.reconciliation.application.ExportSettings;
import com.example.samples.s28.reconciliation.domain.ExportJobId;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The contract at the wire, which is where it either helps a client or does not.
 *
 * <p>Everything asserted here is a decision a caller can be broken by: whether a retried submission looks different
 * from a first one, whether a download link exists before there is anything to download, whether "not finished" and
 * "no such job" are told apart.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"s28.worker.enabled=false"})
@Import(PostgresServiceConnection.class)
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class JobContractTest {

  @Autowired private TestRestTemplate http;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ExportClaims claims;
  @Autowired private ExportRunner runner;
  @Autowired private ExportSettings settings;

  @BeforeEach
  void emptyEverything() {
    jdbc.update("DELETE FROM s28_export_progress");
    jdbc.update("DELETE FROM s28_export_job");
    jdbc.update("DELETE FROM s28_export_row");
    jdbc.update(
        "INSERT INTO s28_export_row (period, order_ref, amount_cents, note)"
            + " SELECT '2026-06', 'ORD-' || g, g, 'settled' FROM generate_series(1, 40) g");
  }

  /** Accepted, not done — and the response says where to look. */
  @Test
  void asubmissionIsAcceptedAndPointsAtTheJob() {
    ResponseEntity<Map> response = submit("exp-1", "2026-06");
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(response.getHeaders().getLocation()).hasToString("/exports/exp-1");
    assertThat(response.getBody()).containsEntry("created", true).containsEntry("status", "QUEUED");
  }

  /**
   * The retry is indistinguishable in status and location, which is the point.
   *
   * <p>A client that timed out and retried must not be able to branch on the difference, because it cannot know which
   * of its two attempts is the one that arrived. Whether this call created the job is in the body, for a human.
   */
  @Test
  void aretriedSubmissionIsTheSameJobAndTheSameAnswer() {
    submit("exp-1", "2026-06");
    ResponseEntity<Map> again = submit("exp-1", "2026-06");
    assertThat(again.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(again.getHeaders().getLocation()).hasToString("/exports/exp-1");
    assertThat(again.getBody()).containsEntry("created", false);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM s28_export_job", Long.class)).isEqualTo(1);
  }

  @Test
  void thesameIdForADifferentPeriodIsAConflict() {
    submit("exp-1", "2026-06");
    assertThat(submit("exp-1", "2026-07").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void pollingaJobThatWasNeverSubmittedIsNotFound() {
    assertThat(http.getForEntity("/exports/nope", Map.class).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  /** The link appears when there is something behind it, and not before. */
  @Test
  void thecontentLinkAppearsOnlyOnceThereIsContent() {
    submit("exp-1", "2026-06");
    assertThat(poll("exp-1").getBody()).containsEntry("contentPath", null);
    assertThat(http.getForEntity("/exports/exp-1/content", String.class).getStatusCode())
        .as("the job exists, so this is a conflict rather than a 404")
        .isEqualTo(HttpStatus.CONFLICT);

    runToCompletion("exp-1");

    assertThat(poll("exp-1").getBody())
        .containsEntry("status", "SUCCEEDED")
        .containsEntry("contentPath", "/exports/exp-1/content")
        .containsEntry("artifactRows", 40);
    ResponseEntity<String> content = http.getForEntity("/exports/exp-1/content", String.class);
    assertThat(content.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(content.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
        .isEqualTo("attachment; filename=\"exp-1.csv\"");
    assertThat(content.getBody().lines()).hasSize(41);
  }

  /** Cancelling is also a request that is accepted rather than carried out. */
  @Test
  void cancellingIsAcceptedAndVisibleOnTheJob() {
    submit("exp-1", "2026-06");
    ResponseEntity<Void> cancelled =
        http.exchange("/exports/exp-1", HttpMethod.DELETE, HttpEntity.EMPTY, Void.class);
    assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(poll("exp-1").getBody())
        .containsEntry("status", "CANCELLED")
        .containsEntry("cancelRequested", true);
  }

  /** The progress reading is on the job resource, so one poll answers both "done?" and "stuck?". */
  @Test
  void thejobResourceCarriesProgressWhileItRuns() {
    submit("exp-1", "2026-06");
    ExportJobId claimed =
        claims.claimNext("worker-a", settings.getLease(), Instant.now()).orElseThrow();
    assertThat(poll("exp-1").getBody()).containsEntry("status", "RUNNING");

    runner.run(claimed, "worker-a");
    Map body = poll("exp-1").getBody();
    assertThat(body).containsEntry("status", "SUCCEEDED").containsEntry("attempt", 1);
    assertThat(body.get("progress")).as("dropped once terminal; the artifact is the authority").isNull();
  }

  /** The synchronous endpoint still exists and still works, which is what makes the comparison honest. */
  @Test
  void theinlineExportAnswersInOneRequest() {
    ResponseEntity<String> response =
        http.getForEntity("/exports/inline?period=2026-06", String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().lines()).hasSize(41);
  }

  private ResponseEntity<Map> submit(String id, String period) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return http.exchange(
        "/exports/" + id,
        HttpMethod.PUT,
        new HttpEntity<>("{\"period\":\"" + period + "\"}", headers),
        Map.class);
  }

  private ResponseEntity<Map> poll(String id) {
    return http.getForEntity("/exports/" + id, Map.class);
  }

  private void runToCompletion(String id) {
    ExportJobId claimed =
        claims.claimNext("worker-a", settings.getLease(), Instant.now()).orElseThrow();
    assertThat(claimed.value()).isEqualTo(id);
    assertThat(runner.run(claimed, "worker-a")).isEqualTo(ExportRunner.Outcome.SUCCEEDED);
  }
}

package com.example.samples.s10;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Shared setup: bring the world up once, and reset both databases between tests.
 *
 * <p>The Docker guard is on the class rather than in a static initialiser, so that a machine without Docker
 * skips instead of failing while loading the class.
 */
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
abstract class DistributedTransactionTestBase {

  protected final RestClient http = RestClient.create();

  @BeforeAll
  static void bootTheWorld() {
    TwoServiceWorld.start();
  }

  @BeforeEach
  void resetBothDatabases() {
    JdbcTemplate accounts = TwoServiceWorld.accountJdbc();
    JdbcTemplate points = TwoServiceWorld.pointsJdbc();

    accounts.update("DELETE FROM undo_log");
    points.update("DELETE FROM undo_log");
    accounts.update(
        "UPDATE s10_account SET balance_minor = 100000, last_note = 'opening', version = 1");
    points.update("DELETE FROM s10_points_entry");
    points.update("UPDATE s10_points_account SET awarded = 0, frozen = 0, version = 1");
  }

  // --- driving the account service ------------------------------------------------------------------

  protected Map<?, ?> purchase(String mode, Map<String, Object> body) {
    return http.post()
        .uri(TwoServiceWorld.accountUrl("/purchases/" + mode))
        .contentType(MediaType.APPLICATION_JSON)
        .header("X-Tenant-Id", TwoServiceWorld.TENANT)
        .body(body)
        .retrieve()
        .body(Map.class);
  }

  protected Throwable purchaseExpectingFailure(String mode, Map<String, Object> body) {
    try {
      purchase(mode, body);
      return null;
    } catch (Throwable t) {
      return t;
    }
  }

  protected Map<String, Object> request(String reference, String accountId) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("reference", reference);
    body.put("accountId", accountId);
    body.put("amountMinor", 2500);
    body.put("points", 25);
    body.put("thenFail", false);
    body.put("holdMillis", 0);
    return body;
  }

  // --- driving the points service directly, which is what a misconfigured caller looks like ---------

  protected int postToPointsDirectly(String path, Map<String, Object> body, String xidOrNull) {
    try {
      RestClient.RequestBodySpec spec =
          http.post()
              .uri(TwoServiceWorld.pointsUrl(path))
              .contentType(MediaType.APPLICATION_JSON)
              .header("X-Tenant-Id", TwoServiceWorld.TENANT);
      if (xidOrNull != null) {
        spec = spec.header("TX_XID", xidOrNull);
      }
      return spec.body(body).retrieve().toBodilessEntity().getStatusCode().value();
    } catch (RestClientResponseException e) {
      return e.getStatusCode().value();
    }
  }

  // --- reading the two databases -------------------------------------------------------------------

  protected long balanceOf(String accountId) {
    return TwoServiceWorld.accountJdbc()
        .queryForObject(
            "SELECT balance_minor FROM s10_account WHERE tenant_id = ? AND id = ?",
            Long.class,
            TwoServiceWorld.TENANT,
            accountId);
  }

  protected long accountVersionOf(String accountId) {
    return TwoServiceWorld.accountJdbc()
        .queryForObject(
            "SELECT version FROM s10_account WHERE tenant_id = ? AND id = ?",
            Long.class,
            TwoServiceWorld.TENANT,
            accountId);
  }

  protected int awardedTo(String pointsAccountId) {
    return TwoServiceWorld.pointsJdbc()
        .queryForObject(
            "SELECT awarded FROM s10_points_account WHERE tenant_id = ? AND account_id = ?",
            Integer.class,
            TwoServiceWorld.TENANT,
            pointsAccountId);
  }

  protected int frozenFor(String pointsAccountId) {
    return TwoServiceWorld.pointsJdbc()
        .queryForObject(
            "SELECT frozen FROM s10_points_account WHERE tenant_id = ? AND account_id = ?",
            Integer.class,
            TwoServiceWorld.TENANT,
            pointsAccountId);
  }

  protected String entryStateOf(String reference) {
    var states =
        TwoServiceWorld.pointsJdbc()
            .queryForList(
                "SELECT state FROM s10_points_entry WHERE tenant_id = ? AND reference = ?",
                String.class,
                TwoServiceWorld.TENANT,
                reference);
    return states.isEmpty() ? null : states.get(0);
  }

  protected int accountUndoLogRows() {
    return TwoServiceWorld.accountJdbc()
        .queryForObject("SELECT count(*) FROM undo_log", Integer.class);
  }

  protected int pointsUndoLogRows() {
    return TwoServiceWorld.pointsJdbc()
        .queryForObject("SELECT count(*) FROM undo_log", Integer.class);
  }

  protected static String chainOf(Throwable throwable) {
    StringBuilder chain = new StringBuilder();
    for (Throwable each = throwable; each != null; each = each.getCause()) {
      chain.append(each.getClass().getSimpleName()).append(": ").append(each.getMessage()).append(" | ");
    }
    return chain.toString();
  }

  protected static HttpHeaders none() {
    return new HttpHeaders();
  }
}

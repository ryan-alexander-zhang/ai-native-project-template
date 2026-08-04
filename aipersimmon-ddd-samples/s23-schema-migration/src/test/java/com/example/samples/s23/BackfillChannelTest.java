package com.example.samples.s23;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Which backfills belong in SQL and which belong in a command — measured, on the two this sample has.
 *
 * <p>The criterion: <strong>restating bytes already in the row is SQL; deciding anything, or having to tell
 * anyone, is a command.</strong> V2's address split is the first case and is a migration file.
 * {@code handling} is the second case twice over, and the four tests below are the four things a command buys
 * that an {@code UPDATE ... CASE WHEN} could not.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresServiceConnection.class)
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class BackfillChannelTest {

  @Autowired private TestRestTemplate http;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM aipersimmon_outbox");
    jdbc.update("DELETE FROM s23_order");
  }

  /**
   * The rule is applied once and lives in one place.
   *
   * <p>A legacy row that predates the column and a freshly placed order with the same inputs come out with the
   * same handling — which is the whole claim. In SQL the migration would carry its own copy of the rule,
   * including the list of remote cities, and the two would agree until the day the carrier added an island.
   */
  @Test
  void thebackfillDecidesByTheSameRuleNewOrdersAreDecidedBy() {
    legacyOrder("legacy-1", "1 Commercial Street", "Shetland", 1);
    legacyOrder("legacy-2", "12 Baker Street", "London", 2);
    legacyOrder("legacy-3", "12 Baker Street", "London", 20);

    assertThat(backfill(100)).isEqualTo(3);

    // Remote destination, small quantity: expedited.
    assertThat(handling("legacy-1")).isEqualTo("EXPEDITED");
    // Neither: standard.
    assertThat(handling("legacy-2")).isEqualTo("STANDARD");
    // Large quantity: expedited.
    assertThat(handling("legacy-3")).isEqualTo("EXPEDITED");

    // And a new order, decided at placement by the same rule, agrees.
    String fresh = place("1 Commercial Street", "Shetland", 1);
    assertThat(handling(fresh)).isEqualTo("EXPEDITED");
  }

  /**
   * It announces what it changed, in the same transaction as the change.
   *
   * <p>The rows being decided are years old, so deciding them changes what downstream should believe about
   * them. An {@code UPDATE} has nobody to tell — no event, no version bump, no way for a consumer to notice
   * that its copy is now wrong. Here the outbox rows and the column values commit together, so an interrupted
   * backfill has neither decided without announcing nor announced without deciding.
   */
  @Test
  void itannouncesEveryRowItDecided() {
    legacyOrder("legacy-1", "1 Commercial Street", "Shetland", 1);
    legacyOrder("legacy-2", "12 Baker Street", "London", 2);

    backfill(100);

    assertThat(announcements())
        .containsExactlyInAnyOrder(
            "com.example.samples.ordering.OrderHandlingDecided",
            "com.example.samples.ordering.OrderHandlingDecided");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM aipersimmon_outbox WHERE subject = 'legacy-1'", Long.class))
        .isEqualTo(1);
  }

  /**
   * Running it again decides nothing and announces nothing.
   *
   * <p>Not a nicety: a backfill over a large table gets restarted — interrupted, run twice by two people, the
   * pod rescheduled — and a step that is not safe to repeat turns a restart into a data question. Idempotence
   * lives in the aggregate ({@code decideHandling} returns false when it has nothing to do), so it holds for
   * every caller rather than for the one that remembered.
   */
  @Test
  void asecondPassIsANoOp() {
    legacyOrder("legacy-1", "1 Commercial Street", "Shetland", 1);
    assertThat(backfill(100)).isEqualTo(1);
    long announced = jdbc.queryForObject("SELECT COUNT(*) FROM aipersimmon_outbox", Long.class);

    assertThat(backfill(100)).isZero();

    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM aipersimmon_outbox", Long.class))
        .isEqualTo(announced);
  }

  /**
   * It goes a page at a time, and the caller loops until zero.
   *
   * <p>Which is what makes it stoppable and observable — each call is one transaction, one page, one number in
   * a log line. A backfill that loads the whole table works until the table is large enough to matter, which is
   * exactly when someone runs it.
   */
  @Test
  void itworksInPagesSoItCanBeStopped() {
    legacyOrder("legacy-1", "1 Commercial Street", "Shetland", 1);
    legacyOrder("legacy-2", "12 Baker Street", "London", 2);
    legacyOrder("legacy-3", "12 Baker Street", "London", 20);

    assertThat(backfill(2)).isEqualTo(2);
    assertThat(backfill(2)).isEqualTo(1);
    assertThat(backfill(2)).isZero();
  }

  /**
   * Until the backfill reaches it, the read side says the handling is unknown rather than guessing.
   *
   * <p>This is what the nullable column costs and what it buys. A {@code DEFAULT 'STANDARD'} in V4 would have
   * removed this state — and with it the backfill's ability to find its own work, and the truth about every
   * legacy order that should have been expedited.
   */
  @Test
  void anundecidedRowReadsAsUndecided() {
    legacyOrder("legacy-1", "1 Commercial Street", "Shetland", 1);

    assertThat(handling("legacy-1")).isNull();
  }

  private void legacyOrder(String id, String street, String city, int quantity) {
    jdbc.update(
        "INSERT INTO s23_order (id, customer_id, sku, quantity, ship_to_street, ship_to_city,"
            + " handling, version) VALUES (?, 'customer-1', 'sku-keyboard', ?, ?, ?, NULL, 1)",
        id,
        quantity,
        street,
        city);
  }

  private String place(String street, String city, int quantity) {
    return JsonPath.read(
        http.postForEntity(
                "/orders",
                Map.of(
                    "customerId", "customer-1",
                    "sku", "sku-keyboard",
                    "quantity", quantity,
                    "street", street,
                    "city", city),
                String.class)
            .getBody(),
        "$.id");
  }

  private int backfill(int batchSize) {
    return JsonPath.read(
        http.postForEntity("/orders/handling-backfill?batchSize=" + batchSize, null, String.class)
            .getBody(),
        "$.decided");
  }

  private String handling(String orderId) {
    return jdbc.queryForObject(
        "SELECT handling FROM s23_order WHERE id = ?", String.class, orderId);
  }

  private List<String> announcements() {
    return jdbc.queryForList("SELECT type FROM aipersimmon_outbox", String.class);
  }
}

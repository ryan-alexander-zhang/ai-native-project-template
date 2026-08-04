package com.example.samples.s23;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipersimmon.ddd.testsupport.ContainerImages;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The three-step change, driven one step at a time with data already in the table.
 *
 * <p>No Spring anywhere. Flyway is invoked directly with a {@code target}, so each test stops the schema at
 * the version a deploy would have reached and asserts what is true <em>at that moment</em> — which is the only
 * way to test expand/contract at all. Applying all four migrations to an empty database, which is what a
 * normal application startup does, proves that the final shape is reachable and nothing whatsoever about
 * whether the path to it was safe.
 *
 * <p>Every test gets its own database, so a step is never observed on a schema another test advanced.
 */
@Testcontainers
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class MigrationStepsTest {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(ContainerImages.POSTGRES);

  private static final AtomicInteger COUNTER = new AtomicInteger();

  private static final String ORDERING = "classpath:db/migration/ordering";

  /**
   * EXPAND: the split fills what it can parse and marks what it cannot.
   *
   * <p>The second row is the one that matters. Free text contains rows nobody anticipated, and a migration
   * that assumed its own data was clean would either fail at 3am or write a street into the city column. The
   * unparseable row lands on the explicit sentinel, which is a decision recorded in the schema rather than a
   * silence — someone can find those rows later and fix them by hand, which is what they will have to do.
   */
  @Test
  void theexpandStepBackfillsWhatItCanParseAndNamesWhatItCannot() {
    String db = freshDatabase();
    migrate(db, "1");

    insertLegacy(db, "order-1", "12 Baker Street, London");
    insertLegacy(db, "order-2", "somewhere behind the bins");

    migrate(db, "2");

    assertThat(query(db, "SELECT ship_to_street FROM s23_order WHERE id = 'order-1'"))
        .containsExactly("12 Baker Street");
    assertThat(query(db, "SELECT ship_to_city FROM s23_order WHERE id = 'order-1'"))
        .containsExactly("London");
    assertThat(query(db, "SELECT ship_to_city FROM s23_order WHERE id = 'order-2'"))
        .containsExactly("UNKNOWN");
    // And the old column is untouched, which is what the still-running code is reading.
    assertThat(query(db, "SELECT ship_to FROM s23_order WHERE id = 'order-1'"))
        .containsExactly("12 Baker Street, London");
  }

  /**
   * EXPAND, and the claim the whole pattern rests on: <strong>the version of the application that is still
   * running keeps working.</strong>
   *
   * <p>This is the assertion that separates expand/contract from "two migrations we deployed together". During
   * a rolling deploy the old instances are still inserting rows in the old shape — no street, no city — and
   * they must succeed. Make the new columns NOT NULL in the same migration that adds them and every one of
   * those inserts fails, for as long as the rollout takes, on the instances nobody has replaced yet.
   */
  @Test
  void theoldCodeKeepsInsertingSuccessfullyAfterTheExpandStep() {
    String db = freshDatabase();
    migrate(db, "2");

    // Exactly what the pre-V2 release writes: it does not know the new columns exist.
    insertLegacy(db, "order-3", "5 Elm Row, Edinburgh");

    assertThat(query(db, "SELECT ship_to FROM s23_order WHERE id = 'order-3'"))
        .containsExactly("5 Elm Row, Edinburgh");
    assertThat(query(db, "SELECT ship_to_city FROM s23_order WHERE id = 'order-3'"))
        .containsExactly((String) null);
  }

  /**
   * CONTRACT: the old column goes, the new ones become required — and the rows the old code wrote in between
   * are caught on the way through.
   *
   * <p>Note the order inside V3: fill the nulls, then constrain. A migration that added NOT NULL without
   * filling first would fail on exactly the rows the expand window produced — the ones written by the old code
   * after V2 and before the new release — which is a failure that only ever happens in the environment that
   * had real traffic during the deploy.
   */
  @Test
  void thecontractStepDropsTheOldColumnAndMakesTheNewOnesRequired() {
    String db = freshDatabase();
    migrate(db, "2");
    insertLegacy(db, "order-4", "5 Elm Row, Edinburgh");

    migrate(db, "3");

    assertThat(columns(db, "s23_order")).doesNotContain("ship_to");
    assertThat(columns(db, "s23_order")).contains("ship_to_street", "ship_to_city");
    // The row the old code wrote during the window was filled rather than rejected.
    assertThat(query(db, "SELECT ship_to_city FROM s23_order WHERE id = 'order-4'"))
        .containsExactly("UNKNOWN");
    // And now the columns are required, so the old shape is genuinely gone.
    assertThatThrownBy(() -> execute(db, "INSERT INTO s23_order (id, customer_id, sku, quantity)"
            + " VALUES ('order-5', 'c', 'sku', 1)"))
        .hasMessageContaining("ship_to_street");
  }

  /**
   * V4 adds a column and refuses to fill it, which is the shape of every backfill that needs a rule.
   *
   * <p>NULL means "not yet decided" and stays that way until the backfill runs — a state the read side has to
   * tolerate, and does. The alternative is a {@code DEFAULT 'STANDARD'}, which would take one line, hold a
   * lock over the whole table, and quietly assert something false about every order that should have been
   * expedited.
   */
  @Test
  void thefourthMigrationAddsAColumnItDeliberatelyDoesNotFill() {
    String db = freshDatabase();
    migrate(db, "2");
    insertLegacy(db, "order-6", "1 Commercial Street, Shetland");

    migrate(db, "4");

    assertThat(columns(db, "s23_order")).contains("handling");
    assertThat(query(db, "SELECT handling FROM s23_order WHERE id = 'order-6'"))
        .containsExactly((String) null);
  }

  /**
   * And the reason the two contexts have separate locations: pointed at their common parent, Flyway refuses.
   *
   * <p>It scans recursively, finds ordering's V1 and billing's V1, and stops — correctly, because there is no
   * answer to "which V1 comes first" when the two version numbers were assigned by different people for
   * unrelated reasons. The failure is the good kind: immediate, at startup, naming the collision. What it
   * rules out is the arrangement where one context renumbers to V5 to get out of the way, and every schema
   * change afterwards is a negotiation.
   */
  @Test
  void twocontextsInOneLocationIsRefusedByFlyway() {
    String db = freshDatabase();

    assertThatThrownBy(
            () ->
                Flyway.configure()
                    .dataSource(url(db), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate())
        .hasMessageContaining("more than one migration with version 1");
  }

  private static void migrate(String db, String target) {
    Flyway.configure()
        .dataSource(url(db), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations(ORDERING)
        .table("flyway_schema_history_ordering")
        .target(target)
        .load()
        .migrate();
  }

  /** An insert in the shape the pre-V2 release writes: the single free-text column, nothing else. */
  private static void insertLegacy(String db, String id, String shipTo) {
    execute(
        db,
        "INSERT INTO s23_order (id, customer_id, sku, quantity, ship_to) VALUES ('"
            + id
            + "', 'customer-1', 'sku-keyboard', 2, '"
            + shipTo
            + "')");
  }

  private static String freshDatabase() {
    String name = "s23steps" + COUNTER.incrementAndGet();
    try (Connection connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE " + name);
    } catch (SQLException e) {
      throw new IllegalStateException("could not create database " + name, e);
    }
    return name;
  }

  private static String url(String db) {
    return "jdbc:postgresql://"
        + POSTGRES.getHost()
        + ":"
        + POSTGRES.getFirstMappedPort()
        + "/"
        + db;
  }

  private static void execute(String db, String sql) {
    try (Connection connection =
            DriverManager.getConnection(url(db), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    } catch (SQLException e) {
      throw new IllegalStateException(e.getMessage(), e);
    }
  }

  private static List<String> query(String db, String sql) {
    List<String> values = new ArrayList<>();
    try (Connection connection =
            DriverManager.getConnection(url(db), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      while (rows.next()) {
        values.add(rows.getString(1));
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e.getMessage(), e);
    }
    return values;
  }

  private static List<String> columns(String db, String table) {
    return query(
        db,
        "SELECT column_name FROM information_schema.columns WHERE table_name = '" + table + "'");
  }
}

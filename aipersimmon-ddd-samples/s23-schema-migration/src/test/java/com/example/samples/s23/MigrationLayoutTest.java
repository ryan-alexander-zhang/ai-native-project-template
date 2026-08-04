package com.example.samples.s23;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Three sets of migrations, one database, three history tables — and no shared version space anywhere.
 *
 * <p>This is the layout question answered by inspection rather than by argument: after a normal startup, what
 * is actually in the database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresServiceConnection.class)
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class MigrationLayoutTest {

  @Autowired private JdbcTemplate jdbc;

  /** Each owner has its own history table, and nobody owns the unnamed default. */
  @Test
  void eachOwnerHasItsOwnHistoryTable() {
    List<String> tables = tables();

    assertThat(tables)
        .contains(
            "flyway_schema_history_ordering",
            "flyway_schema_history_billing",
            "flyway_schema_history_aipersimmon_outbox");
    // The default name is unclaimed on purpose. Left to the first context, it becomes indistinguishable
    // from "the application's history" the day a second context arrives.
    assertThat(tables).doesNotContain("flyway_schema_history");
  }

  /**
   * Both contexts have a V1, which is the point of separating them.
   *
   * <p>Two owners numbering from 1 is the natural state of affairs — their version numbers were assigned by
   * different people for unrelated reasons — and it is only possible because neither shares a version space
   * with the other. {@code MigrationStepsTest} asserts the other half: pointed at their common parent, Flyway
   * refuses outright.
   *
   * <p>Billing's history begins with a <strong>baseline row at version 0</strong>, and ordering's does not.
   * That asymmetry was measured rather than expected, and it is a property of running second: by the time
   * billing migrates, ordering's tables exist, so the schema is not empty and Flyway will only proceed with
   * {@code baselineOnMigrate}. Which in turn is why the baseline version has to be <em>0</em> — the framework's
   * own migrator says so in its properties, and the reason is the same here: a baseline at 1 would mark
   * billing's V1 as already applied, and {@code s23_invoice} would never be created. Silently, on a schema
   * that looks fine.
   */
  @Test
  void bothcontextsNumberFromOneWithoutColliding() {
    // First to run, on an empty schema: no baseline needed.
    assertThat(versions("flyway_schema_history_ordering")).containsExactly("1", "2", "3", "4");
    // Second to run, on a schema that already has tables: baseline, then its own V1.
    assertThat(versions("flyway_schema_history_billing")).containsExactly("0", "1");
  }

  /**
   * The framework's component ran too — which is not free, because defining a migration strategy of our own
   * made the library's back off.
   *
   * <p>Its history is its own as well, so a component's version space is independent of both contexts'. That
   * is what lets the library ship a V5 for the outbox without asking anybody what number is free.
   */
  @Test
  void theframeworkComponentAppliedIntoItsOwnHistory() {
    assertThat(tables()).contains("aipersimmon_outbox", "aipersimmon_dead_letter", "shedlock");
    assertThat(versions("flyway_schema_history_aipersimmon_outbox")).isNotEmpty();
  }

  /** Both contexts' business tables are here, and neither is in the other's history. */
  @Test
  void bothcontextsGotTheirTables() {
    assertThat(tables()).contains("s23_order", "s23_invoice");
    assertThat(descriptions("flyway_schema_history_ordering"))
        .noneMatch(description -> description.contains("invoice"));
    assertThat(descriptions("flyway_schema_history_billing"))
        .noneMatch(description -> description.contains("order"));
  }

  private List<String> tables() {
    return jdbc.queryForList(
        "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
        String.class);
  }

  private List<String> versions(String historyTable) {
    return jdbc.queryForList(
        "SELECT version FROM " + historyTable + " WHERE version IS NOT NULL ORDER BY installed_rank",
        String.class);
  }

  private List<String> descriptions(String historyTable) {
    return jdbc.queryForList("SELECT description FROM " + historyTable, String.class);
  }
}

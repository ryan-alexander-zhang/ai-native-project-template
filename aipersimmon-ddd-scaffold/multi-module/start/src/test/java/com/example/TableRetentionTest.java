package com.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

/**
 * Every table this application creates has an answer to "how long do rows live here?" — even when
 * the answer is "forever" (issue-00097).
 *
 * <p>The table that prompted this is {@code payment_operations}. It arrived as a fix for something
 * else and grew at the rate of the order book with nothing to trim it, while the three framework
 * tables it is a sibling to all had retention configured a few lines apart in {@code
 * application.yml}. What made it easy to miss is that it replaced a {@code ConcurrentHashMap}: the
 * map's retention policy was "the process restarts and it is empty", which nobody had written down
 * because it was never chosen. Substituting durable storage took that policy away and left no gap
 * where it had been.
 *
 * <p>Hence a guard that is about the next table rather than this one. It discovers tables from the
 * migrations, so a new {@code CREATE TABLE} fails here until somebody writes down which kind of
 * table it is. "Kept forever, because it is the business's data" is a perfectly good answer and
 * most of the entries below are exactly that; what is not an answer is silence, which is what this
 * table had.
 *
 * <p>Scope is this application's own migrations. The framework's tables — outbox, inbox, operation
 * log, process manager — are created by component migrations and configured under {@code
 * aipersimmon.ddd.*}; they had their retention decided by whoever wrote those components.
 *
 * <p>A plain unit test: no Spring context, no container. Like {@code MigrationContentTest} it reads
 * the source tree rather than the classpath, so it checks the current source and not the last
 * build.
 */
class TableRetentionTest {

  private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");
  private static final Path CONFIGURATION = Path.of("src/main/resources/application.yml");

  private static final Pattern CREATE_TABLE =
      Pattern.compile("CREATE\\s+TABLE\\s+([A-Za-z0-9_.]+)", Pattern.CASE_INSENSITIVE);

  /**
   * What happens to old rows in each table. {@code purgedAfter} names the property that configures
   * the window, and that property has to exist; {@code keptForever} is the deliberate opposite, and
   * has to say why.
   */
  private static final Map<String, Decision> DECISIONS =
      Map.of(
          "ordering.customers",
              Decision.keptForever("a customer and their credit limit are business data"),
          "ordering.orders", Decision.keptForever("the order book; deleting it loses the business"),
          "ordering.order_lines", Decision.keptForever("owned by ordering.orders, same lifetime"),
          "inventory.stocks", Decision.keptForever("current stock per SKU; there is no old row"),
          "inventory.reservations",
              Decision.keptForever(
                  "a reservation is the evidence behind a confirmed or compensated order"),
          "inventory.reservation_lines",
              Decision.keptForever("owned by inventory.reservations, same lifetime"),
          "payment.payment_operations",
              Decision.purgedAfter("payment.operations.cleanup.retention-seconds"));

  @Test
  void everyTableThisApplicationCreatesHasARetentionDecision() throws IOException {
    Set<String> created = tablesCreatedByMigrations();

    Set<String> undecided = new TreeSet<>(created);
    undecided.removeAll(DECISIONS.keySet());
    assertTrue(
        undecided.isEmpty(),
        () ->
            "these tables have no retention decision: "
                + undecided
                + ". Add one to DECISIONS. An append-only table needs a window and the property"
                + " that configures it; a table holding business data is kept forever and says so."
                + " Both are answers — the state this guard exists to prevent is neither.");

    Set<String> vanished = new TreeSet<>(DECISIONS.keySet());
    vanished.removeAll(created);
    assertEquals(
        Set.of(), vanished, "DECISIONS names tables no migration creates; drop the stale entries");
  }

  @Test
  void everyConfiguredRetentionWindowActuallyExists() {
    Properties configuration = configuredProperties();

    for (Map.Entry<String, Decision> decided : DECISIONS.entrySet()) {
      String property = decided.getValue().propertyKey();
      if (property == null) {
        continue;
      }
      assertNotNull(
          configuration.getProperty(property),
          () ->
              decided.getKey()
                  + " is documented as purged after "
                  + property
                  + ", but no such property is set in application.yml — the decision exists only"
                  + " in this test, and the rows would grow forever");
    }
  }

  private static Set<String> tablesCreatedByMigrations() throws IOException {
    assertTrue(
        Files.isDirectory(MIGRATIONS),
        () ->
            "expected the migrations at "
                + MIGRATIONS.toAbsolutePath()
                + " — this test reads the source tree and assumes the module directory is the"
                + " working directory");

    List<Path> migrations;
    try (Stream<Path> tree = Files.walk(MIGRATIONS)) {
      migrations = tree.filter(path -> path.toString().endsWith(".sql")).sorted().toList();
    }

    Set<String> tables = new TreeSet<>();
    for (Path migration : migrations) {
      Matcher statements = CREATE_TABLE.matcher(Files.readString(migration));
      while (statements.find()) {
        tables.add(statements.group(1).toLowerCase(Locale.ROOT));
      }
    }
    assertFalse(tables.isEmpty(), "no tables found — the guard would pass vacuously");
    return tables;
  }

  private static Properties configuredProperties() {
    YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
    yaml.setResources(new FileSystemResource(CONFIGURATION.toFile()));
    Properties flattened = yaml.getObject();
    assertNotNull(flattened, () -> "could not read " + CONFIGURATION.toAbsolutePath());
    return flattened;
  }

  /** Either a window, named by the property that sets it, or a reason for keeping rows forever. */
  private record Decision(String propertyKey, String reason) {
    static Decision purgedAfter(String propertyKey) {
      return new Decision(propertyKey, null);
    }

    static Decision keptForever(String reason) {
      return new Decision(null, reason);
    }
  }
}

package com.example;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A versioned migration describes structure; it does not carry data.
 *
 * <p>There is no natural failing test for {@code issue-00072}, because the demo seed is something
 * the test suite <em>wants</em> to exist — seven acceptance tests still place orders for {@code
 * CUST-1}. What was wrong was never the seed itself but its location: {@code db/migration} runs
 * exactly once in every environment Flyway is pointed at, with no way to opt out, so a project
 * copied from this scaffold gets a customer named Acme in its production database on the first
 * deployment. The seed now lives in {@code db/dev}, which only the dev profile loads.
 *
 * <p>So this is a structural assertion standing in for a behavioural one, and it is a regression
 * guard first: the next person to reach for a quick {@code INSERT} in a migration finds out here
 * rather than in production. That is worth more than the one-time move it verifies.
 *
 * <p>A plain unit test — no Spring context, no container. It reads the source tree rather than the
 * classpath on purpose: a stale copy under {@code target/classes} would happily answer a classpath
 * scan and the guard would be checking the last build instead of the current source.
 */
class MigrationContentTest {

  private static final Path VERSIONED = Path.of("src/main/resources/db/migration");

  @Test
  void noVersionedMigrationCarriesData() throws IOException {
    assertTrue(
        Files.isDirectory(VERSIONED),
        () ->
            "expected the migrations at "
                + VERSIONED.toAbsolutePath()
                + " — this test reads the source tree and assumes the module directory is the"
                + " working directory");

    List<Path> migrations;
    try (Stream<Path> tree = Files.walk(VERSIONED)) {
      migrations = tree.filter(p -> p.toString().endsWith(".sql")).sorted().toList();
    }
    assertFalse(migrations.isEmpty(), "no migrations found — the guard would pass vacuously");

    for (Path migration : migrations) {
      String sql = Files.readString(migration).toUpperCase(Locale.ROOT);
      assertFalse(
          sql.contains("INSERT INTO"),
          () ->
              migration
                  + " carries data. A versioned migration runs once in EVERY environment and"
                  + " cannot be switched off per profile, so demo or seed rows placed here reach"
                  + " production too — put them in db/dev, which only the dev profile loads"
                  + " (issue-00072).");
    }
  }
}

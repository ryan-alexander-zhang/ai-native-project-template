package com.aipersimmon.ddd.flyway;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.support.JdbcUtils;

/**
 * Applies the aipersimmon-ddd component schemas a consumer asked for, each with its own dedicated
 * Flyway instance and history table.
 *
 * <p><strong>Bundling is not enabling.</strong> Nothing is applied until {@code
 * aipersimmon.ddd.flyway.components} names a component. Creating tables in someone's database is an
 * outward-facing, hard-to-reverse act, and a bundle starter puts five components' migrations on the
 * classpath at once — so "on the classpath" must not mean "write DDL". The cost of the opt-in is
 * one config line, and forgetting it fails loudly at startup: each component's schema validator
 * refuses to start and names both the migration path and the key to set. The cost of the opposite
 * default would be paid silently, by a production schema that grew a dozen tables nobody asked for.
 *
 * <p>Migrations ship with their owning module at {@code
 * classpath:aipersimmon/db/migration/{component}/{vendor}/V*.sql} — deliberately NOT under {@code
 * db/migration}, so Spring Boot's default Flyway (which scans {@code classpath:db/migration}) never
 * sees them and never trips over the multiple {@code V1}s. This runner resolves the database vendor
 * from the {@link DataSource}, scans the classpath for the component sets present for that vendor,
 * and migrates the selected ones into their own history tables ({@code <prefix><component>}). Being
 * schema-agnostic, it needs no dependency on the storage modules; the classpath scan is what a
 * selection is checked against, so a name that ships no migrations is reported rather than ignored.
 *
 * <p>It is invoked from a {@link
 * org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy} AFTER the consumer's own
 * default Flyway migrations have run, so aipersimmon tables never collide with — nor share a
 * version history with — the consumer's own schema.
 */
public final class AipersimmonFlywayMigrator {

  private static final Logger log = LoggerFactory.getLogger(AipersimmonFlywayMigrator.class);

  private static final String BASE = "aipersimmon/db/migration";

  private final AipersimmonFlywayProperties properties;

  public AipersimmonFlywayMigrator(AipersimmonFlywayProperties properties) {
    this.properties = properties;
  }

  /** Apply the selected aipersimmon component migrations against the given data source. */
  public void migrate(DataSource dataSource) {
    String vendor = resolveVendor(dataSource);
    TreeSet<String> discovered = discoverComponents(vendor);
    if (discovered.isEmpty()) {
      log.info("aipersimmon-ddd Flyway: no component migrations found for vendor '{}'", vendor);
      return;
    }
    if (properties.getComponents().isEmpty()) {
      // Say what was available and how to ask for it. Silence here would look like the runner is
      // broken; the alternative — creating all of it — is not ours to decide (see the class
      // javadoc).
      log.info(
          "aipersimmon-ddd Flyway: applying nothing because aipersimmon.ddd.flyway.components is"
              + " empty. Components available on the classpath for vendor '{}': {}. List the ones"
              + " this application owns, or leave it empty and apply"
              + " classpath:{}/<component>/{}/V*.sql with your own tool.",
          vendor,
          discovered,
          BASE,
          vendor);
      return;
    }
    warnAboutUnknownSelections(discovered, vendor);
    for (String component : discovered) {
      if (!isSelected(component)) {
        continue;
      }
      String location = "classpath:" + BASE + "/" + component + "/" + vendor;
      String historyTable = properties.getHistoryTablePrefix() + component.replace('-', '_');
      Flyway flyway =
          Flyway.configure(getClass().getClassLoader())
              .dataSource(dataSource)
              .locations(location)
              .table(historyTable)
              .baselineOnMigrate(properties.isBaselineOnMigrate())
              .baselineVersion(properties.getBaselineVersion())
              .load();
      MigrateResult result = flyway.migrate();
      log.info(
          "aipersimmon-ddd Flyway: component '{}' applied {} migration(s) from {} via history table '{}'",
          component,
          result.migrationsExecuted,
          location,
          historyTable);
    }
  }

  private boolean isSelected(String component) {
    return properties.getComponents().contains(component);
  }

  /**
   * A configured name that matches nothing on the classpath is almost always a typo or a module the
   * consumer forgot to add — and its symptom is a missing table at the first write, far from here.
   * Warn rather than fail: the runner does not know whether the component is meant to arrive later
   * (a staged rollout), and refusing to start would be a worse answer to a spelling mistake.
   */
  private void warnAboutUnknownSelections(TreeSet<String> discovered, String vendor) {
    for (String requested : properties.getComponents()) {
      if (!discovered.contains(requested)) {
        log.warn(
            "aipersimmon-ddd Flyway: component '{}' is listed under"
                + " aipersimmon.ddd.flyway.components but ships no migrations for vendor '{}'."
                + " Available: {}. Its tables will NOT be created.",
            requested,
            vendor,
            discovered);
      }
    }
  }

  /** Scan the classpath for {@code aipersimmon/db/migration/<component>/<vendor>/*.sql} sets. */
  private TreeSet<String> discoverComponents(String vendor) {
    TreeSet<String> components = new TreeSet<>();
    Pattern pattern =
        Pattern.compile(Pattern.quote(BASE) + "/([^/]+)/" + Pattern.quote(vendor) + "/");
    PathMatchingResourcePatternResolver resolver =
        new PathMatchingResourcePatternResolver(getClass().getClassLoader());
    try {
      Resource[] resources =
          resolver.getResources("classpath*:" + BASE + "/*/" + vendor + "/*.sql");
      for (Resource resource : resources) {
        String path = resource.getURL().getPath();
        Matcher matcher = pattern.matcher(path);
        if (matcher.find()) {
          components.add(matcher.group(1));
        }
      }
    } catch (IOException ex) {
      throw new IllegalStateException(
          "Failed to scan the classpath for aipersimmon-ddd migrations", ex);
    }
    return components;
  }

  /**
   * Map the DataSource's database product to the Flyway vendor token used in the migration paths.
   */
  private String resolveVendor(DataSource dataSource) {
    String product;
    try (Connection connection = dataSource.getConnection()) {
      product = JdbcUtils.commonDatabaseName(connection.getMetaData().getDatabaseProductName());
    } catch (SQLException ex) {
      throw new IllegalStateException(
          "Cannot determine the database vendor for aipersimmon-ddd Flyway migration", ex);
    }
    String name = product == null ? "" : product.toLowerCase(Locale.ROOT);
    if (name.contains("h2")) {
      return "h2";
    }
    if (name.contains("postgresql")) {
      return "postgresql";
    }
    if (name.contains("mysql") || name.contains("mariadb")) {
      return "mysql";
    }
    throw new IllegalStateException(
        "Unsupported database for aipersimmon-ddd Flyway migration: '"
            + product
            + "'. Supported vendors: h2, postgresql, mysql. Set aipersimmon.ddd.flyway.enabled=false"
            + " and apply the schema yourself.");
  }
}

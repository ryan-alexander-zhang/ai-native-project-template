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
 * Discovers and applies every aipersimmon-ddd component schema, each with its own dedicated Flyway
 * instance and history table.
 *
 * <p>Migrations ship with their owning module at {@code
 * classpath:aipersimmon/db/migration/{component}/{vendor}/V*.sql} — deliberately NOT under {@code
 * db/migration}, so Spring Boot's default Flyway (which scans {@code classpath:db/migration}) never
 * sees them and never trips over the multiple {@code V1}s. This runner resolves the database vendor
 * from the {@link DataSource}, scans the classpath for the component sets present for that vendor,
 * and migrates each into its own history table ({@code <prefix><component>}). Being
 * schema-agnostic, it needs no dependency on the storage modules — it applies exactly the ones the
 * consumer actually put on the classpath.
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

  /** Apply every discovered aipersimmon component migration against the given data source. */
  public void migrate(DataSource dataSource) {
    String vendor = resolveVendor(dataSource);
    TreeSet<String> components = discoverComponents(vendor);
    if (components.isEmpty()) {
      log.info("aipersimmon-ddd Flyway: no component migrations found for vendor '{}'", vendor);
      return;
    }
    for (String component : components) {
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
    return properties.getComponents().isEmpty() || properties.getComponents().contains(component);
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

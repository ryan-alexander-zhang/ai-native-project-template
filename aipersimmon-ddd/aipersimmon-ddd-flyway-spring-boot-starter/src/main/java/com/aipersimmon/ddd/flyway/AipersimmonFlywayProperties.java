package com.aipersimmon.ddd.flyway;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the shared aipersimmon-ddd Flyway runner, under {@code aipersimmon.ddd.flyway}.
 * The defaults are chosen so that having this module on the classpath applies <em>nothing</em>
 * until {@link #getComponents() components} says what to apply.
 */
@ConfigurationProperties(prefix = "aipersimmon.ddd.flyway")
public class AipersimmonFlywayProperties {

  /** Run the shared Flyway migration at startup. Applies only what {@code components} lists. */
  private boolean enabled = true;

  /**
   * The component schemas this application owns, by name ({@code outbox}, {@code inbox}, {@code
   * process-manager}, {@code operation-log}, {@code web-store}).
   *
   * <p>Empty — the default — applies nothing: a bundle starter puts every component's migrations on
   * the classpath, and being on the classpath must not mean writing DDL to a database. Name the
   * ones you want, or leave it empty and copy {@code
   * aipersimmon/db/migration/<component>/<vendor>/} into your own migration tool. A component whose
   * tables are missing fails at startup, not at the first write.
   */
  private List<String> components = List.of();

  /** Baseline a non-empty schema so this can be adopted on an existing database. */
  private boolean baselineOnMigrate = true;

  /**
   * Baseline version. Must sort BELOW the first component migration ({@code V1}); otherwise a
   * baseline would mark {@code V1} as already applied and the tables would never be created. Hence
   * {@code 0}.
   */
  private String baselineVersion = "0";

  /**
   * Prefix for each component's DEDICATED history table; the component name (with hyphens turned
   * into underscores) is appended, e.g. {@code flyway_schema_history_aipersimmon_outbox}. Keeping a
   * table per component means aipersimmon migrations never share a version space with the
   * consumer's own migrations, nor with each other.
   */
  private String historyTablePrefix = "flyway_schema_history_aipersimmon_";

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public List<String> getComponents() {
    return components;
  }

  public void setComponents(List<String> components) {
    this.components = components;
  }

  public boolean isBaselineOnMigrate() {
    return baselineOnMigrate;
  }

  public void setBaselineOnMigrate(boolean baselineOnMigrate) {
    this.baselineOnMigrate = baselineOnMigrate;
  }

  public String getBaselineVersion() {
    return baselineVersion;
  }

  public void setBaselineVersion(String baselineVersion) {
    this.baselineVersion = baselineVersion;
  }

  public String getHistoryTablePrefix() {
    return historyTablePrefix;
  }

  public void setHistoryTablePrefix(String historyTablePrefix) {
    this.historyTablePrefix = historyTablePrefix;
  }
}

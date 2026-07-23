package com.aipersimmon.ddd.operationlog.engine.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration under {@code aipersimmon.ddd.operation-log}. {@code source} defaults to the
 * application name when blank (resolved in the auto-configuration); {@code tenant.enabled} turns on
 * multi-tenant enforcement; {@code limits.*} are the pipeline size budgets.
 */
@ConfigurationProperties(prefix = "aipersimmon.ddd.operation-log")
public class OperationLogProperties {

  /** Stable logical producer identity; when blank the auto-config falls back to the app name. */
  private String source = "";

  private final Tenant tenant = new Tenant();
  private final Limits limits = new Limits();

  public String getSource() {
    return source;
  }

  public void setSource(String source) {
    this.source = source;
  }

  public Tenant getTenant() {
    return tenant;
  }

  public Limits getLimits() {
    return limits;
  }

  /** Multi-tenant settings. */
  public static class Tenant {
    /** When true, tenant is mandatory on write, unique key, and all reads. */
    private boolean enabled;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }
  }

  /** Pipeline size budgets. */
  public static class Limits {
    /** Maximum rendered summary length. */
    private int summaryMaxChars = 1024;

    /** Maximum number of recorded changes. */
    private int maxChanges = 20;

    /** Maximum number of recorded details. */
    private int maxDetails = 20;

    /** Maximum length of a single change/detail value. */
    private int maxValueChars = 512;

    public int getSummaryMaxChars() {
      return summaryMaxChars;
    }

    public void setSummaryMaxChars(int summaryMaxChars) {
      this.summaryMaxChars = summaryMaxChars;
    }

    public int getMaxChanges() {
      return maxChanges;
    }

    public void setMaxChanges(int maxChanges) {
      this.maxChanges = maxChanges;
    }

    public int getMaxDetails() {
      return maxDetails;
    }

    public void setMaxDetails(int maxDetails) {
      this.maxDetails = maxDetails;
    }

    public int getMaxValueChars() {
      return maxValueChars;
    }

    public void setMaxValueChars(int maxValueChars) {
      this.maxValueChars = maxValueChars;
    }
  }
}

package com.aipersimmon.ddd.operationlog.engine.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration under {@code aipersimmon.ddd.operation-log}. {@code source} defaults to the
 * application name when blank (resolved in the auto-configuration); {@code limits.*} are the
 * pipeline size budgets.
 *
 * <p>Deliberately no tenant switch here. The tenant column is always stamped: the default {@code
 * OperationTenantResolver} reads {@code TenantContext.effective()}, so enforcement follows the
 * deployment-wide {@code aipersimmon.ddd.tenancy.enabled} — with tenancy on, an unbound tenant
 * fails the command rather than stamping the sentinel; with tenancy off, rows carry {@code
 * __root__}. A per-component flag once declared here was read by nothing and promised enforcement
 * this class never provided; a second switch for the same question would only let the two disagree.
 */
@ConfigurationProperties(prefix = "aipersimmon.ddd.operation-log")
public class OperationLogProperties {

  /** Stable logical producer identity; when blank the auto-config falls back to the app name. */
  private String source = "";

  private final Limits limits = new Limits();

  public String getSource() {
    return source;
  }

  public void setSource(String source) {
    this.source = source;
  }

  public Limits getLimits() {
    return limits;
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

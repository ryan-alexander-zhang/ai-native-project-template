package com.aipersimmon.ddd.operationlog.port;

import java.time.Instant;
import java.util.Objects;

/**
 * Bounded query criteria. {@code tenantId} is mandatory so multi-tenant reads can never span
 * tenants; time and target bounds keep queries bounded. The read side is implemented in P3.
 */
public record OperationLogCriteria(
    String tenantId, String targetType, String targetId, Instant from, Instant to, int pageSize) {

  /**
   * @throws NullPointerException if {@code tenantId} is null
   */
  public OperationLogCriteria {
    Objects.requireNonNull(tenantId, "tenantId");
  }

  /** Criteria for one target's history within a time window. */
  public static OperationLogCriteria forTarget(
      String tenantId, String targetType, String targetId, Instant from, Instant to, int pageSize) {
    return new OperationLogCriteria(tenantId, targetType, targetId, from, to, pageSize);
  }
}

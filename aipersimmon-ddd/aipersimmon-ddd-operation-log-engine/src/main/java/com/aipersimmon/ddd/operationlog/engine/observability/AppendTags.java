package com.aipersimmon.ddd.operationlog.engine.observability;

import java.util.Objects;

/**
 * The low-cardinality label set for the append-side counters: the business {@code operationCode},
 * the {@code outcome} ({@code SUCCEEDED}/{@code REJECTED}/{@code FAILED}), and the {@code sinkType}
 * (the backend's simple class name). These are the only dimensions an {@link OperationLogMetrics}
 * implementation may turn into metric labels — none of them is per-request or per-entity, so the
 * label space stays bounded. Never carries {@code recordId}, {@code messageId}, {@code tenantId},
 * or any other high-cardinality identity; those correlate through logs and trace attributes
 * instead.
 *
 * @param operationCode the stable business operation code
 * @param outcome the operation outcome name
 * @param sinkType the backend sink's simple class name
 */
public record AppendTags(String operationCode, String outcome, String sinkType) {

  public AppendTags {
    Objects.requireNonNull(operationCode, "operationCode");
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(sinkType, "sinkType");
  }
}

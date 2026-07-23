/**
 * The metrics seam for the Operation Log component: {@link
 * com.aipersimmon.ddd.operationlog.engine.observability.OperationLogMetrics} and its
 * low-cardinality label carrier {@link
 * com.aipersimmon.ddd.operationlog.engine.observability.AppendTags}, with a no-op default ({@link
 * com.aipersimmon.ddd.operationlog.engine.observability.NoOpOperationLogMetrics}). A plain SPI so
 * the engine and capture layer emit metrics without a compile dependency on any metrics runtime; a
 * consumer bridges it to its meter registry by supplying a bean.
 */
package com.aipersimmon.ddd.operationlog.engine.observability;

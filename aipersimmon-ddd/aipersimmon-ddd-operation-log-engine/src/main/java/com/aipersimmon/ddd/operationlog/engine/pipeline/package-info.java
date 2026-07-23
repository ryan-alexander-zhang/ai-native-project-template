/**
 * The storage-agnostic {@code OperationLogs} pipeline: {@link
 * com.aipersimmon.ddd.operationlog.engine.pipeline.DefaultOperationLogs} normalizes, redacts,
 * derives the idempotency key, freezes a draft into an entry, and appends it via the sink. Pure
 * Java — no Spring, no JDBC.
 */
package com.aipersimmon.ddd.operationlog.engine.pipeline;

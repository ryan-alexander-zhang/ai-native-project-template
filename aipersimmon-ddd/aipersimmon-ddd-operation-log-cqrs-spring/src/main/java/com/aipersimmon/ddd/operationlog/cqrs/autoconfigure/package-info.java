/**
 * Spring Boot auto-configuration for the Operation Log capture layer: scans for
 * {@code @OperationLog} command types, builds the definition registry (fail-fast on conflicts), and
 * wires the two interceptors, the invocation factory, and the failure-path seams. Backs off
 * gracefully when no {@code OperationLogs} pipeline (i.e. no storage backend) is present.
 */
package com.aipersimmon.ddd.operationlog.cqrs.autoconfigure;

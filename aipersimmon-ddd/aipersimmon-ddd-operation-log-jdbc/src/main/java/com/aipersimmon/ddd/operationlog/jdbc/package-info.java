/**
 * JDBC storage backend for the Operation Log component: {@link
 * com.aipersimmon.ddd.operationlog.jdbc.JdbcOperationLogSink} over {@code JdbcTemplate}, with
 * dialect-native duplicate convergence ({@link
 * com.aipersimmon.ddd.operationlog.jdbc.PostgresOperationLogDialect} uses {@code ON CONFLICT DO
 * NOTHING} so the caller's transaction is never aborted; {@link
 * com.aipersimmon.ddd.operationlog.jdbc.DefaultOperationLogDialect} catches the duplicate on
 * engines that do statement-level rollback). Carries no DDL — the shared migrations live in the
 * engine module.
 */
package com.aipersimmon.ddd.operationlog.jdbc;

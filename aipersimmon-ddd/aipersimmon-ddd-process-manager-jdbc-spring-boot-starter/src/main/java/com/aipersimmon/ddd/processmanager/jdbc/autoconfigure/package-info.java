/**
 * Spring Boot auto-configuration for the JDBC durable Process Manager. The
 * {@link com.aipersimmon.ddd.processmanager.jdbc.autoconfigure.AipersimmonDddProcessManagerJdbcAutoConfiguration}
 * collects the consumer's explicitly-registered Definitions, Codecs, and Dispatchers,
 * selects the {@code JdbcProcessDialect}
 * ({@link com.aipersimmon.ddd.processmanager.jdbc.autoconfigure.ProcessDialectFactory}),
 * wires the runtime/query/relay/deadline-worker/operations, validates
 * {@link com.aipersimmon.ddd.processmanager.jdbc.autoconfigure.ProcessManagerJdbcProperties}
 * and the schema at startup
 * ({@link com.aipersimmon.ddd.processmanager.jdbc.autoconfigure.ProcessSchemaValidator}),
 * and runs the workers via
 * {@link com.aipersimmon.ddd.processmanager.jdbc.autoconfigure.ProcessWorkerScheduler}.
 * Every bean is overridable; no business package is scanned and no DDL is executed.
 */
package com.aipersimmon.ddd.processmanager.jdbc.autoconfigure;

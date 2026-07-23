/**
 * Storage-agnostic Spring Boot auto-configuration for the durable Process Manager. The {@link
 * com.aipersimmon.ddd.processmanager.engine.autoconfigure.AipersimmonDddProcessManagerAutoConfiguration}
 * collects the consumer's explicitly-registered Definitions, Codecs, and Dispatchers into their
 * registries, then — once a storage backend has contributed the four store beans and a {@link
 * com.aipersimmon.ddd.processmanager.engine.lease.ProcessClaimStrategy} — wires the runtime, query,
 * operations, effect relay, and deadline worker, and runs the workers via {@link
 * com.aipersimmon.ddd.processmanager.engine.autoconfigure.ProcessWorkerScheduler}. It validates
 * {@link com.aipersimmon.ddd.processmanager.engine.autoconfigure.ProcessManagerProperties} and the
 * registries at startup ({@link
 * com.aipersimmon.ddd.processmanager.engine.autoconfigure.ProcessManagerStartupValidator}), and
 * exposes optional Micrometer/Actuator observability. Every bean is overridable; no business
 * package is scanned and no DDL is executed. A storage backend
 * (aipersimmon-ddd-process-manager-jdbc, aipersimmon-ddd-process-manager-mybatis-plus) supplies the
 * store adapters and is ordered before this class.
 */
package com.aipersimmon.ddd.processmanager.engine.autoconfigure;

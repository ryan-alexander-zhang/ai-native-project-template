/**
 * MyBatis-Plus storage backend for the durable Process Manager. It implements the engine's store
 * and claim ports over MyBatis-Plus mappers and auto-configures them, so the storage-agnostic
 * {@code aipersimmon-ddd-process-manager-engine} wires the runtime, relay, and deadline worker on
 * top. Behavior is identical to {@code aipersimmon-ddd-process-manager-jdbc}: the same four-table
 * SQL, the same optimistic-revision and lease fencing, and the same SKIP LOCKED / atomic claim.
 */
package com.aipersimmon.ddd.processmanager.mybatisplus;

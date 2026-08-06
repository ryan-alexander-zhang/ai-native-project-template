/**
 * MyBatis-Plus storage backend for the durable Process Manager. It implements the engine's store
 * and claim ports over MyBatis-Plus mappers and auto-configures them, so the storage-agnostic
 * {@code aipersimmon-ddd-process-manager-engine} wires the runtime, relay, and deadline worker on
 * top. It is the only process-manager storage backend the library ships, and it owns the whole
 * four-table SQL surface: optimistic-revision and lease fencing, and the SKIP LOCKED / atomic
 * claim.
 */
package com.aipersimmon.ddd.processmanager.mybatisplus;

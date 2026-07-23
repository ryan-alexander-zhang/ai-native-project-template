/**
 * The JDBC runtime that advances a durable process atomically and answers read queries: {@link
 * com.aipersimmon.ddd.processmanager.jdbc.runtime.JdbcProcessRuntime} runs the pure definition and
 * persists the snapshot, transition, effects, and deadline changes in one {@link
 * com.aipersimmon.ddd.processmanager.jdbc.runtime.JdbcProcessUnitOfWork} ({@code REQUIRED})
 * transaction; effects are delivered afterwards by a relay. {@link
 * com.aipersimmon.ddd.processmanager.jdbc.runtime.JdbcProcessQuery} reads the snapshot.
 *
 * <p>Effects are staged with their durable identity ({@code messageId == effectId}), so
 * at-least-once redelivery keeps one stable id. {@code start} idempotency for a repeated business
 * key is governed by {@link
 * com.aipersimmon.ddd.processmanager.jdbc.runtime.DuplicateBusinessKeyPolicy}.
 */
package com.aipersimmon.ddd.processmanager.jdbc.runtime;

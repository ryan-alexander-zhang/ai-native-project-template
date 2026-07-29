/**
 * The one port the outbox engine runs on. {@link
 * com.aipersimmon.ddd.outbox.engine.store.OutboxStore} is every row-level operation the writer,
 * relay and cleanup need and nothing more: the decisions about which rows are due, in what order,
 * and what a failure means stay in the engine, so both storage backends make them identically.
 */
package com.aipersimmon.ddd.outbox.engine.store;

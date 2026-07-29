/**
 * The shared Spring wiring: one auto-configuration that assembles the writer, relay, schedule and
 * cleanup over whatever {@link com.aipersimmon.ddd.outbox.engine.store.OutboxStore} a storage
 * backend contributed. A backend registers its store and its dead-letter beans; everything else is
 * here.
 */
package com.aipersimmon.ddd.outbox.engine.autoconfigure;

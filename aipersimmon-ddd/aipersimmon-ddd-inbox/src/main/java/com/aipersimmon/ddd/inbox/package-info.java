/**
 * Storage-agnostic inbox core, symmetrical with {@link com.aipersimmon.ddd.outbox}: the idempotency
 * contract ({@link com.aipersimmon.ddd.inbox.Inbox}) a consumer calls before handling a message,
 * shared by every storage backend. Persistence lives in a storage adapter that depends on this core
 * ({@code -inbox-jdbc}, {@code -inbox-mybatis-plus}); the table both adapters write to is shipped
 * by this module as vendor-templated Flyway migrations.
 *
 * <p>Depends on nothing, so an application can name the port without committing to a backend.
 */
package com.aipersimmon.ddd.inbox;

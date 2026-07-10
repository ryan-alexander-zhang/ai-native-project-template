/**
 * Transactional-outbox storage over MyBatis-Plus:
 * {@link com.aipersimmon.ddd.outbox.mybatisplus.OutboxWriter} inserts an
 * integration event into the outbox table in the caller's transaction (through the
 * {@link com.aipersimmon.ddd.outbox.mybatisplus.OutboxMapper} over the
 * {@link com.aipersimmon.ddd.outbox.mybatisplus.OutboxRecord} entity), and
 * {@link com.aipersimmon.ddd.outbox.mybatisplus.OutboxRelay} dispatches unsent rows
 * through an {@link com.aipersimmon.ddd.outbox.OutboxDispatcher} (from the
 * storage-agnostic outbox core) and marks them sent. Uses MyBatis-Plus annotations,
 * not a JPA {@code @Entity}, and registers only its own mapper, so it never affects
 * a consumer's entity scanning or {@code @MapperScan}.
 */
package com.aipersimmon.ddd.outbox.mybatisplus;

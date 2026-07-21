/**
 * Idempotent-consumer implementation over MyBatis-Plus: {@link
 * com.aipersimmon.ddd.inbox.mybatisplus.MybatisPlusInbox} records each handled message key in an
 * inbox table (through the {@link com.aipersimmon.ddd.inbox.mybatisplus.InboxMapper} over the
 * {@link com.aipersimmon.ddd.inbox.mybatisplus.InboxRecord} entity) with a unique constraint, so a
 * redelivered message is detected and skipped. Uses MyBatis-Plus annotations, not a JPA
 * {@code @Entity}, and registers only its own mapper, so it never affects a consumer's entity
 * scanning or {@code @MapperScan}.
 */
package com.aipersimmon.ddd.inbox.mybatisplus;

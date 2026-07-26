/**
 * Aggregate persistence for MyBatis-Plus: a template-method repository base ({@link
 * com.aipersimmon.ddd.persistence.mybatisplus.MybatisPlusAggregateRepository}) that writes the root
 * under its optimistic-lock version and drains the aggregate's domain events, plus the
 * optimistic-locker contribution that supplies the {@code WHERE version = ?} predicate.
 *
 * <p>No generic repository port is provided: the domain declares its own per aggregate, and a
 * framework-imposed one would pull {@code findAll}/{@code update}-shaped operations into the domain
 * language. Only the write path is shared, because only the write path carries the invariants.
 */
package com.aipersimmon.ddd.persistence.mybatisplus;

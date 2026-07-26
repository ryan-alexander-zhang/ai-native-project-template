/**
 * Aggregate persistence for plain JDBC: a template-method repository base ({@link
 * com.aipersimmon.ddd.persistence.jdbc.JdbcAggregateRepository}) that centralises the affected-rows
 * check and the domain-event drain.
 *
 * <p>With no declarative {@code @Version} available, the subclass writes the {@code UPDATE}; the
 * base hands it the expected version as a parameter so the {@code WHERE version = ?} predicate is
 * hard to omit by accident.
 */
package com.aipersimmon.ddd.persistence.jdbc;

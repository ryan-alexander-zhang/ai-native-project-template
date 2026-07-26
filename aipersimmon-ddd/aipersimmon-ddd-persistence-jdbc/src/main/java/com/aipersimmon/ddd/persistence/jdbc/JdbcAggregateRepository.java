package com.aipersimmon.ddd.persistence.jdbc;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;
import java.util.Objects;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * Base for a plain-JDBC repository of one aggregate: it centralises the affected-rows check and the
 * domain-event drain, so a version-checked write and event publishing are the default rather than
 * something each repository re-implements.
 *
 * <p>Plain JDBC has no declarative {@code @Version}, so the subclass writes the SQL. The base hands
 * it the expected version as a parameter rather than leaving it to be remembered:
 *
 * <pre>{@code
 * @Override protected int update(Order order, long expectedVersion) {
 *   return jdbc.update(
 *       "UPDATE ordering.orders SET status = ?, version = ? WHERE id = ? AND version = ?",
 *       order.status().name(), expectedVersion + 1, order.id().value(), expectedVersion);
 * }
 * }</pre>
 *
 * <p>The {@code WHERE version = ?} predicate is the subclass's responsibility, and it is the whole
 * point: without it the update always matches, the affected-rows check passes, and a write from a
 * stale snapshot silently discards a concurrent change. An unused {@code expectedVersion} parameter
 * is the signal that it was forgotten.
 *
 * <p>Like its MyBatis-Plus sibling this is a base class, not a port — the domain declares its own
 * repository interface, and reads stay with the subclass.
 *
 * @param <A> the aggregate root type
 */
public abstract class JdbcAggregateRepository<A extends AbstractAggregateRoot<?>> {

  private final DomainEvents domainEvents;

  protected JdbcAggregateRepository(DomainEvents domainEvents) {
    this.domainEvents = Objects.requireNonNull(domainEvents, "domainEvents");
  }

  /**
   * Persist {@code aggregate} under its optimistic-lock version, then publish and clear the events
   * it recorded. Call this from the domain port's {@code save}.
   *
   * <p>A not-yet-persisted aggregate ({@code version == 0}) goes to {@link #insert}; an existing
   * one to {@link #update}, whose {@code WHERE} clause must include the expected version. Either
   * must report the number of rows it affected; {@code 0} on update means the aggregate was
   * modified concurrently and the write is refused.
   *
   * <p>Runs in the caller's transaction, so rows and events commit or roll back together.
   *
   * @throws OptimisticLockingFailureException if the aggregate was modified concurrently
   */
  protected final void saveAggregate(A aggregate) {
    long expected = aggregate.version();
    if (expected == 0) {
      insert(aggregate);
    } else if (update(aggregate, expected) == 0) {
      throw new OptimisticLockingFailureException(
          "aggregate "
              + aggregate.getClass().getSimpleName()
              + "["
              + aggregate.id()
              + "] was modified concurrently (expected version "
              + expected
              + ")");
    }
    aggregate.versionAdvanced();
    domainEvents.publishAndClear(aggregate);
  }

  /**
   * Insert a not-yet-persisted aggregate, writing version {@code 1}, plus any child rows.
   *
   * @return the number of root rows inserted
   */
  protected abstract int insert(A aggregate);

  /**
   * Update an existing aggregate, plus any child rows. The {@code WHERE} clause
   * <strong>must</strong> carry {@code version = expectedVersion} and the {@code SET} clause must
   * advance it, or the concurrency check does nothing.
   *
   * @param expectedVersion the version the aggregate was loaded at
   * @return the number of root rows updated — {@code 0} means the version did not match
   */
  protected abstract int update(A aggregate, long expectedVersion);
}

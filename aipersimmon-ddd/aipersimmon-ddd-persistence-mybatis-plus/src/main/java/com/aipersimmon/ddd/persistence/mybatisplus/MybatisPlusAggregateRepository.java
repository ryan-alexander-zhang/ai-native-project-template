package com.aipersimmon.ddd.persistence.mybatisplus;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.Objects;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * Base for a MyBatis-Plus repository of one aggregate. It makes the two things that are easy to get
 * wrong the default: the write is version-checked, and the aggregate's recorded domain events are
 * drained as part of saving.
 *
 * <p>A subclass supplies the mapping and, if the aggregate has child tables, how to write them:
 *
 * <pre>{@code
 * @Repository
 * public class MyBatisOrders extends MybatisPlusAggregateRepository<Order, OrderDo>
 *     implements Orders {
 *
 *   private final OrderLineMapper lines;
 *
 *   public MyBatisOrders(OrderMapper orders, OrderLineMapper lines, DomainEvents domainEvents) {
 *     super(orders, domainEvents);
 *     this.lines = lines;
 *   }
 *
 *   @Override public void save(Order order) { saveAggregate(order); }
 *   @Override protected OrderDo toRow(Order order) { ... }
 *   @Override protected void saveChildren(Order order) { ... rewrite order_lines ... }
 *   @Override public Optional<Order> findById(OrderId id) { ... Order.reconstitute(..., row.getVersion()) ... }
 * }
 * }</pre>
 *
 * <p>It is a base class, not a port: the domain still declares its own repository interface. A
 * generic {@code AggregateRepository<A, ID>} would push {@code findAll}/{@code update}-shaped
 * operations into the domain language, which is what modelling a repository per aggregate avoids.
 * Reads stay entirely with the subclass — only the write path is shared, because only the write
 * path carries the invariants.
 *
 * <p>Requires the optimistic-locker interceptor to be installed, which {@code
 * AipersimmonDddPersistenceMybatisPlusAutoConfiguration} contributes. Without it the {@code WHERE
 * version = ?} predicate is never added and {@link #saveAggregate} silently degrades to a
 * last-writer-wins update — see {@code design-00011} §3.
 *
 * @param <A> the aggregate root type
 * @param <D> the row type for the root's own table
 */
public abstract class MybatisPlusAggregateRepository<
    A extends AbstractAggregateRoot<?>, D extends VersionedRow> {

  private final BaseMapper<D> mapper;
  private final DomainEvents domainEvents;

  protected MybatisPlusAggregateRepository(BaseMapper<D> mapper, DomainEvents domainEvents) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.domainEvents = Objects.requireNonNull(domainEvents, "domainEvents");
  }

  /**
   * Persist {@code aggregate} under its optimistic-lock version, write its children, then publish
   * and clear the events it recorded. Call this from the domain port's {@code save}.
   *
   * <p>A not-yet-persisted aggregate ({@code version == 0}) is inserted at version 1 — no existence
   * query, so there is no window between checking and inserting. An existing one is updated with
   * {@code WHERE version = loaded}; matching no row means another transaction moved the aggregate
   * on since it was loaded, so the write is refused rather than allowed to discard that change.
   *
   * <p>Runs in the caller's transaction (the command bus opens it), so the row, the child rows and
   * the events commit or roll back together.
   *
   * @throws OptimisticLockingFailureException if the aggregate was modified concurrently
   */
  protected final void saveAggregate(A aggregate) {
    D row = toRow(aggregate);
    long expected = aggregate.version();
    if (expected == 0) {
      row.setVersion(1L);
      mapper.insert(row);
    } else {
      row.setVersion(expected);
      if (mapper.updateById(row) == 0) {
        throw new OptimisticLockingFailureException(
            "aggregate "
                + aggregate.getClass().getSimpleName()
                + "["
                + aggregate.id()
                + "] was modified concurrently (expected version "
                + expected
                + ")");
      }
    }
    saveChildren(aggregate);
    aggregate.versionAdvanced();
    domainEvents.publishAndClear(aggregate);
  }

  /** Map the aggregate onto its own table's row. The version is set by {@link #saveAggregate}. */
  protected abstract D toRow(A aggregate);

  /**
   * Write the aggregate's child tables, if it has any. Called after the root row is written, so it
   * runs only when the version check passed. Default: nothing to do.
   */
  protected void saveChildren(A aggregate) {
    // no children by default
  }
}

package com.aipersimmon.ddd.persistence.mybatisplus;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.application.DuplicateEntityException;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
 * version = ?} predicate is never added and the update degrades to last-writer-wins. {@link
 * #saveAggregate} no longer takes that on trust: it checks the interceptor's own witness (the
 * version it writes back onto the row) and fails loudly, because the affected-rows check cannot
 * detect a missing predicate — it passes precisely because the predicate is gone.
 *
 * <p>It also requires an active transaction, for the same reason it publishes events: the root row,
 * the child rows and the events are one atomic outcome or they are a corrupted aggregate.
 *
 * <h2>Two premises about the table, both of which bite on a schema this class did not design</h2>
 *
 * <p><strong>{@code version == 0} means "never persisted".</strong> {@link #saveAggregate} reads it
 * as the insert signal, so it is not a value a stored row may hold. When a version column is added
 * to a table that already has rows, its default must therefore be {@code 1}:
 *
 * <pre>{@code
 * ALTER TABLE legacy_refunds ADD COLUMN version BIGINT NOT NULL DEFAULT 1;  -- not 0
 * }</pre>
 *
 * <p>With {@code DEFAULT 0} every pre-existing row reads back as unsaved, and the first write to
 * any of them is an insert of a row that already exists — a {@link DuplicateEntityException} whose
 * cause is the column default rather than anything the code did.
 *
 * <p><strong>A second writer to the same table must advance the version itself.</strong> Some
 * writes legitimately bypass {@code saveAggregate} — claiming a row for a worker ({@code FOR UPDATE
 * SKIP LOCKED} plus a conditional {@code UPDATE}, where losing the race is not information and so
 * must not raise), a batch data repair, an operations script. Every such statement has to include
 * {@code version = version + 1}:
 *
 * <pre>{@code
 * UPDATE export_jobs SET state = 'RUNNING', version = version + 1 WHERE id = ? AND state = 'QUEUED'
 * }</pre>
 *
 * <p>Omit it and a transaction that loaded the aggregate before the claim still commits — it checks
 * the version, and the version genuinely is unchanged. Nothing throws, and the result does not look
 * like a concurrency defect afterwards; it looks like a write that succeeded. This is a premise,
 * not something this class can enforce: a claim has to bypass the version check to be useful at
 * all, and {@code saveAggregate} cannot know who else writes the table.
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
   * @throws DuplicateEntityException if the insert hit a row that already exists
   */
  protected final void saveAggregate(A aggregate) {
    requireActiveTransaction(aggregate);
    D row = toRow(aggregate);
    long expected = aggregate.version();
    if (expected == 0) {
      row.setVersion(1L);
      insertExactlyOnce(row, aggregate);
    } else {
      row.setVersion(expected);
      if (update(row) == 0) {
        throw new OptimisticLockingFailureException(
            "aggregate "
                + aggregate.getClass().getSimpleName()
                + "["
                + aggregate.id()
                + "] was modified concurrently (expected version "
                + expected
                + ")");
      }
      requireVersionWasChecked(row, expected, aggregate);
    }
    saveChildren(aggregate);
    aggregate.versionAdvanced();
    domainEvents.publishAndClear(aggregate);
  }

  /**
   * Insert the row and refuse to continue unless the mapper wrote exactly it.
   *
   * <p>Zero rows means the aggregate was not saved, and save must not fall through to writing
   * children, advancing the version and publishing events for a row the database never gained. A
   * mapper reports zero when its insert statement swallows the conflict — an {@code INSERT ... ON
   * CONFLICT DO NOTHING} or {@code INSERT IGNORE}; a conflict-tolerant insert belongs behind an
   * explicit application-level check, not silently inside save.
   *
   * <p>The row must already carry its primary key, checked before the statement runs. MyBatis-Plus
   * would otherwise let an {@code IdType.AUTO} column be filled by the database, and the insert
   * would <em>succeed</em> with an identity the application never learns — leaving the in-memory
   * aggregate, the events already published under its id, and the row disagreeing about what was
   * just created. Failing here instead makes that a refusal at the write rather than a
   * misattribution discovered one write later.
   *
   * <p>A duplicate key is rethrown as {@link DuplicateEntityException} naming the plausible causes,
   * because the stack trace alone cannot distinguish them: a genuine race between two creates of
   * the same identity, an update that mistakenly took this branch, or a stored row whose version
   * column defaults to 0.
   */
  private void insertExactlyOnce(D row, A aggregate) {
    requirePrimaryKeyBeforeInsert(row, aggregate);
    int inserted;
    try {
      inserted = mapper.insert(row);
    } catch (DuplicateKeyException e) {
      throw new DuplicateEntityException(
          "aggregate "
              + aggregate.getClass().getSimpleName()
              + "["
              + aggregate.id()
              + "] already exists. Either two concurrent creates raced on the same identity — a"
              + " genuine conflict the client should see as 409 — or this aggregate was"
              + " reconstituted by a factory that forgot to call restoreVersion(...), leaving its"
              + " version at 0 so save took the insert branch; if this write was meant to be an"
              + " update, that is the bug to fix. A third cause looks like neither: the row comes"
              + " from a table whose version column was added with DEFAULT 0, so every row that"
              + " predates that migration reads back as never-persisted. Retrofit such a column with"
              + " DEFAULT 1.",
          e);
    }
    if (inserted == 0) {
      throw new IllegalStateException(
          "insert of aggregate "
              + aggregate.getClass().getSimpleName()
              + "["
              + aggregate.id()
              + "] reported zero rows (an INSERT IGNORE or INSERT ... ON CONFLICT DO NOTHING does"
              + " this on a duplicate): the aggregate was NOT saved and its events must not be"
              + " published. A conflict-tolerant insert belongs behind an explicit"
              + " application-level check, not silently inside save.");
    }
  }

  /**
   * Write the root row, including the columns the aggregate has emptied.
   *
   * <p>{@code updateById} alone would not: MyBatis-Plus leaves a null field out of the {@code SET}
   * clause, which is right for a partial update and wrong for this one. {@link #toRow} maps the
   * whole root, so a null here means the aggregate cleared that field — and dropping the assignment
   * leaves the old value in the database while everything reports success. The version really did
   * move, so the optimistic-lock check passes; the events really do publish, so downstream is told
   * the change happened; and the old value comes back on the next load, undoing part of a command
   * that was accepted. This is the trap this base class exists to neutralise, and the store the
   * framework wrote for its own outbox already sidesteps it the same way.
   *
   * <p>So the update goes through a wrapper carrying the emptied columns explicitly, keyed by id.
   * The entity is still passed: it supplies every other column, and — the part that matters — the
   * optimistic-lock interceptor keys on it, appending the {@code version} predicate to this wrapper
   * and writing the incremented version back for {@link #requireVersionWasChecked} to inspect. Both
   * halves of the guarantee are therefore unchanged.
   */
  private int update(D row) {
    TableInfo tableInfo = TableInfoHelper.getTableInfo(row.getClass());
    UpdateWrapper<D> wrapper = new UpdateWrapper<>();
    wrapper.eq(idColumnOf(tableInfo, row), idValueOf(tableInfo, row));
    ClearedColumns.forceOnto(wrapper, row);
    return mapper.update(row, wrapper);
  }

  private String idColumnOf(TableInfo tableInfo, D row) {
    if (tableInfo == null || tableInfo.getKeyColumn() == null) {
      throw new IllegalStateException(
          row.getClass().getName()
              + " has no MyBatis-Plus primary key, so its update has nothing to key on. Annotate"
              + " the identity field with @TableId.");
    }
    return tableInfo.getKeyColumn();
  }

  private Object idValueOf(TableInfo tableInfo, D row) {
    Object id = tableInfo.getPropertyValue(row, tableInfo.getKeyProperty());
    if (id == null) {
      throw new IllegalStateException(
          row.getClass().getName()
              + " came back from toRow with no primary key value, so an update would match every"
              + " row of the table. Map the aggregate's identity onto the row.");
    }
    return id;
  }

  /**
   * Refuse an insert whose row has no primary key.
   *
   * <p>The update path checks this because a missing key would match every row. The insert path
   * checks it for a different and less visible reason: the statement would <em>work</em>. A column
   * declared {@code IdType.AUTO} over {@code BIGSERIAL} or {@code AUTO_INCREMENT} is filled by the
   * database, so the row lands, {@link #saveAggregate} advances the version, and the events publish
   * — all under the identity the aggregate was constructed with, which is not the identity in the
   * table. Nothing fails until the next write to the same aggregate, by which point the
   * misattribution has committed and reached whatever consumed those events.
   *
   * <p>So the identity has to be known <em>before</em> the insert, not after it. Reserve it from
   * the table's own sequence ({@code nextval(pg_get_serial_sequence('t', 'id'))}) and map it onto
   * the row; do not read it back afterwards, which would mean mutating an aggregate's id after its
   * events were published.
   */
  private void requirePrimaryKeyBeforeInsert(D row, A aggregate) {
    TableInfo tableInfo = TableInfoHelper.getTableInfo(row.getClass());
    if (tableInfo == null || tableInfo.getKeyProperty() == null) {
      throw new IllegalStateException(
          row.getClass().getName()
              + " has no MyBatis-Plus primary key, so its identity cannot be established before the"
              + " insert. Annotate the identity field with @TableId.");
    }
    if (tableInfo.getPropertyValue(row, tableInfo.getKeyProperty()) != null) {
      return;
    }
    throw new IllegalStateException(
        row.getClass().getName()
            + " came back from toRow with no primary key value while inserting aggregate "
            + aggregate.getClass().getSimpleName()
            + "["
            + aggregate.id()
            + "]. If this table's primary key is assigned by the database (BIGSERIAL,"
            + " AUTO_INCREMENT), reserve the value yourself before inserting — for example"
            + " nextval(pg_get_serial_sequence('the_table', 'id')) — and map it onto the row. This"
            + " write path has to know the identity before the insert, because the aggregate's events"
            + " and its version are both recorded against it, and an id read back afterwards would"
            + " arrive too late for either.");
  }

  /**
   * Refuse to write outside a transaction.
   *
   * <p>{@link #saveAggregate} makes three writes that only mean something together: the root row,
   * the child rows, and the domain events. Untransacted, each commits on its own — a failure
   * between them leaves the aggregate half-written with events already published for a state the
   * database never reached, and there is nothing left to roll back. The javadoc has always said
   * "runs in the caller's transaction"; this is that sentence made enforceable, because the failure
   * it describes is invisible until the day something throws midway.
   */
  private void requireActiveTransaction(A aggregate) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      return;
    }
    throw new IllegalStateException(
        "no active transaction while saving aggregate "
            + aggregate.getClass().getSimpleName()
            + "["
            + aggregate.id()
            + "]: the root row, its child rows and its domain events must commit or roll back"
            + " together. Send the operation through the CommandBus (its transaction interceptor"
            + " opens one), or annotate the calling application service with @Transactional.");
  }

  /**
   * Prove the optimistic-locker interceptor actually rewrote this update.
   *
   * <p>Its rewrite has a witness: after building {@code SET version = version + 1 ... WHERE version
   * = ?} the interceptor writes the incremented value back onto the entity. So a row that comes
   * back still holding the version we set means no {@code WHERE version} predicate was added — the
   * update matched its row unconditionally, reported one row changed, and a writer working from a
   * stale snapshot just discarded a concurrent change. The affected-rows check above cannot see
   * that: it passes precisely because the predicate is missing.
   *
   * <p>Two ways to arrive here, both silent until now. A consumer declaring their own {@code
   * MybatisPlusInterceptor} takes over assembly wholesale, so the framework's contribution backs
   * off — and the log line announcing it lives in the bean that backed off. Or the row's version
   * field lacks MyBatis-Plus's {@code @Version} annotation, which is what the interceptor keys on,
   * so it finds nothing to rewrite. The message names both.
   */
  private void requireVersionWasChecked(D row, long expected, A aggregate) {
    Long written = row.getVersion();
    if (written != null && written == expected + 1) {
      return;
    }
    throw new IllegalStateException(
        "optimistic locking is not in effect for "
            + row.getClass().getName()
            + ": updating aggregate "
            + aggregate.getClass().getSimpleName()
            + "["
            + aggregate.id()
            + "] at version "
            + expected
            + " left the row at version "
            + written
            + " instead of "
            + (expected + 1)
            + ", so no 'WHERE version = ?' predicate was applied and the write would have"
            + " overwritten any concurrent change. Either the row's version field is missing"
            + " MyBatis-Plus's @Version annotation, or the OptimisticLockerInnerInterceptor is not"
            + " installed — a consumer-declared MybatisPlusInterceptor bean replaces the"
            + " framework's assembly entirely, and must add the interceptors it displaced.");
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

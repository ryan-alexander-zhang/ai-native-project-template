package com.example.samples.s10.points.infrastructure;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.persistence.mybatisplus.MybatisPlusAggregateRepository;
import com.example.samples.s10.points.domain.PointsAccount;
import com.example.samples.s10.points.domain.PointsAccountId;
import com.example.samples.s10.points.domain.PointsAccounts;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * The points adapter, and the reason it is worth reading in a sample about distributed transactions:
 * <strong>there is nothing distributed in it.</strong>
 *
 * <p>Ordinary MyBatis-Plus, over an ordinary {@code DataSource}, through the framework's ordinary
 * version-checked base class. Seata's data-source proxy sits underneath all of it and sees only the final
 * SQL — after the optimistic locker has appended {@code SET version = version + 1 ... WHERE version = ?}
 * and after the tenant line has appended {@code AND tenant_id = ?}. That layering is why the two can
 * coexist, and it was measured rather than assumed: the undo log's before-image carries {@code version} as
 * an ordinary column and both key columns as the primary key.
 */
@Repository
class MyBatisPointsAccounts extends MybatisPlusAggregateRepository<PointsAccount, PointsAccountRow>
    implements PointsAccounts {

  private final PointsAccountMapper accounts;
  private final PointsEntryMapper entries;

  MyBatisPointsAccounts(
      PointsAccountMapper accounts, PointsEntryMapper entries, DomainEvents domainEvents) {
    super(accounts, domainEvents);
    this.accounts = accounts;
    this.entries = entries;
  }

  @Override
  public Optional<PointsAccount> find(PointsAccountId id, String reference) {
    PointsAccountRow row = accounts.selectById(id.value());
    if (row == null) {
      return Optional.empty();
    }
    PointsEntryRow entryRow = entries.selectById(reference);
    PointsAccount.Entry entry =
        entryRow == null || !entryRow.getAccountId().equals(id.value())
            ? null
            : new PointsAccount.Entry(
                entryRow.getReference(),
                entryRow.getPoints(),
                PointsAccount.EntryState.valueOf(entryRow.getState()));
    return Optional.of(
        PointsAccount.reconstitute(
            id, row.getAwarded(), row.getFrozen(), entry, row.getVersion()));
  }

  @Override
  public void save(PointsAccount account) {
    saveAggregate(account);
  }

  @Override
  protected PointsAccountRow toRow(PointsAccount account) {
    PointsAccountRow row = new PointsAccountRow();
    row.setAccountId(account.id().value());
    row.setAwarded(account.awarded());
    row.setFrozen(account.frozen());
    return row;
  }

  /**
   * The one entry this load was about, written as an upsert-by-hand.
   *
   * <p>Deliberately not a blind insert: Confirm and Cancel both rewrite an existing reference's state, and
   * a redelivered Try must not raise a duplicate key. Deliberately not a delete-then-reinsert either — the
   * entry's history is the participant's whole idempotency story, and the row's identity is what a late
   * Try is refused by.
   */
  @Override
  protected void saveChildren(PointsAccount account) {
    account
        .entry()
        .ifPresent(
            entry -> {
              PointsEntryRow row = new PointsEntryRow();
              row.setReference(entry.reference());
              row.setAccountId(account.id().value());
              row.setPoints(entry.points());
              row.setState(entry.state().name());
              if (entries.selectById(entry.reference()) == null) {
                entries.insert(row);
              } else {
                entries.updateById(row);
              }
            });
  }
}

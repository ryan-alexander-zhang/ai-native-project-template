package com.example.samples.s10.banking.infrastructure;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.persistence.mybatisplus.MybatisPlusAggregateRepository;
import com.example.samples.s10.banking.domain.Account;
import com.example.samples.s10.banking.domain.AccountId;
import com.example.samples.s10.banking.domain.Accounts;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * The account adapter. Unchanged by the presence of a distributed transaction — see the note in the points
 * service's adapter for why that is the interesting part rather than an omission.
 */
@Repository
class MyBatisAccounts extends MybatisPlusAggregateRepository<Account, AccountRow>
    implements Accounts {

  private final AccountMapper mapper;

  MyBatisAccounts(AccountMapper mapper, DomainEvents domainEvents) {
    super(mapper, domainEvents);
    this.mapper = mapper;
  }

  @Override
  public Optional<Account> find(AccountId id) {
    AccountRow row = mapper.selectById(id.value());
    if (row == null) {
      return Optional.empty();
    }
    return Optional.of(
        Account.reconstitute(id, row.getBalanceMinor(), row.getLastNote(), row.getVersion()));
  }

  @Override
  public void save(Account account) {
    saveAggregate(account);
  }

  @Override
  protected AccountRow toRow(Account account) {
    AccountRow row = new AccountRow();
    row.setId(account.id().value());
    row.setBalanceMinor(account.balanceMinor());
    row.setLastNote(account.lastNote());
    return row;
  }
}

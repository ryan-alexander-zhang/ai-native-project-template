package com.example.samples.s10.banking.domain;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;

/**
 * An account and its balance.
 *
 * <p><strong>What is deliberately missing: any notion of a pending or reserved debit.</strong> That is the
 * whole claim S10 makes about strong consistency. Under AT the money is simply gone the moment this method
 * returns, and if the rest of the business transaction fails, Seata puts the row back — so the model never
 * has to hold a state like "debited, awaiting points". Compare with {@code PointsAccount.frozen} in the
 * other service, which exists precisely because TCC does not offer that.
 *
 * <p>So the trade is not "AT is easier": it is <em>AT keeps the intermediate state out of the model and
 * puts it in a lock; TCC takes it out of the lock and puts it in the model.</em> A domain that already has
 * a word for the intermediate state loses nothing by choosing TCC. A domain that does not — this one; a
 * balance is a balance — pays for TCC in vocabulary it had no use for.
 */
@AggregateRoot
public final class Account extends AbstractAggregateRoot<AccountId> {

  private final AccountId id;
  private long balanceMinor;
  private String lastNote;

  private Account(AccountId id, long balanceMinor, String lastNote) {
    this.id = id;
    this.balanceMinor = balanceMinor;
    this.lastNote = lastNote;
  }

  public static Account reconstitute(
      AccountId id, long balanceMinor, String lastNote, long version) {
    Account account = new Account(id, balanceMinor, lastNote);
    account.restoreVersion(version);
    return account;
  }

  /**
   * Take the money.
   *
   * <p>Throws rather than returning an outcome, unlike the participants in S9. The difference is the
   * caller: this one is a synchronous request the customer is waiting on, so "not enough money" is an
   * answer to give the customer, not a fact for a coordinator to compensate for. An outcome enum here
   * would have to be turned back into an exception one line later anyway.
   *
   * @param note free text, and it may be null — which is what makes the write exercise the framework's
   *     cleared-column path, and therefore what makes Seata's parser see an explicit {@code SET ... =
   *     NULL}.
   */
  public void debit(long amountMinor, String note) {
    if (amountMinor <= 0) {
      throw new IllegalArgumentException("amount must be positive: " + amountMinor);
    }
    if (amountMinor > balanceMinor) {
      throw new InsufficientFundsException(id, balanceMinor, amountMinor);
    }
    this.balanceMinor -= amountMinor;
    this.lastNote = note;
  }

  @Override
  public AccountId id() {
    return id;
  }

  public long balanceMinor() {
    return balanceMinor;
  }

  public String lastNote() {
    return lastNote;
  }
}

package com.example.samples.s10.banking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.samples.s10.banking.domain.Account;
import com.example.samples.s10.banking.domain.AccountId;
import com.example.samples.s10.banking.domain.InsufficientFundsException;
import org.junit.jupiter.api.Test;

/**
 * The account's rules — all four lines of them.
 *
 * <p>This file is short on purpose, and its shortness is the AT argument stated as code. There is nothing here
 * about reservations, holds, pending debits or compensation, because under AT the coordinator supplies all of
 * that. The same use case done with TCC would have put three more methods and one more column in this class,
 * which is exactly what the points service's model shows.
 */
class AccountTest {

  private static Account account(long balance) {
    return Account.reconstitute(new AccountId("customer-1"), balance, "opening", 1);
  }

  @Test
  void adebitTakesTheMoneyAndRecordsTheReference() {
    Account account = account(10000);

    account.debit(2500, "buy-1");

    assertThat(account.balanceMinor()).isEqualTo(7500);
    assertThat(account.lastNote()).isEqualTo("buy-1");
  }

  @Test
  void adebitLargerThanTheBalanceIsRefused() {
    Account account = account(1000);

    assertThatThrownBy(() -> account.debit(2500, "buy-1"))
        .isInstanceOf(InsufficientFundsException.class);
    assertThat(account.balanceMinor()).isEqualTo(1000);
  }

  /**
   * A note may be cleared, and that is not incidental.
   *
   * <p>A null here makes the framework's repository write an explicit {@code SET last_note = NULL} rather
   * than omitting the column, which is the case Seata's SQL parser then has to capture in its after-image.
   * Measured in the end-to-end tests: it does.
   */
  @Test
  void anoteCanBeCleared() {
    Account account = account(10000);

    account.debit(1, null);

    assertThat(account.lastNote()).isNull();
  }

  @Test
  void anonPositiveDebitIsAProgrammingError() {
    assertThatThrownBy(() -> account(10000).debit(0, "buy-1"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}

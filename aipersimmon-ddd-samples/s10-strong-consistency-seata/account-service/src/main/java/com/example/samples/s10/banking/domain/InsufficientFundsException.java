package com.example.samples.s10.banking.domain;

import com.aipersimmon.ddd.core.exception.DomainException;

/** Not enough money. A refusal the customer sees, not a failure a coordinator compensates for. */
public final class InsufficientFundsException extends DomainException {

  public InsufficientFundsException(AccountId id, long balanceMinor, long requestedMinor) {
    super(
        BankingErrorCode.INSUFFICIENT_FUNDS,
        "account "
            + id.value()
            + " holds "
            + balanceMinor
            + " and cannot pay "
            + requestedMinor);
  }
}

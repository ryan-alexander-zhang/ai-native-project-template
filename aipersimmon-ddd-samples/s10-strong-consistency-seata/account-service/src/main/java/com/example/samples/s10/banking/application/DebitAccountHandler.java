package com.example.samples.s10.banking.application;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s10.banking.domain.Account;
import com.example.samples.s10.banking.domain.AccountId;
import com.example.samples.s10.banking.domain.Accounts;
import com.example.samples.s10.banking.domain.BankingErrorCode;
import org.springframework.stereotype.Component;

/**
 * The debit, written as if nothing else existed.
 *
 * <p>There is no XID here, no branch, no compensation and no awareness that another service is about to be
 * asked for something. The bus's interceptor opens a local transaction; Seata's data-source proxy turns
 * that transaction into a branch of whatever global transaction the calling thread is in — or into an
 * ordinary local transaction if it is in none. Both are correct, and the handler cannot tell the
 * difference.
 *
 * <p>Which is the honest version of "AT is transparent": <em>the transparency is real for the handler and
 * false for the operator</em>. Everything this handler does not know is knowledge that has to live
 * somewhere, and it now lives in the deployment — the coordinator, the undo log table, the lock table, and
 * the two properties that let a caller drop the XID.
 */
@Component
class DebitAccountHandler implements CommandHandler<DebitAccount, Void> {

  private final Accounts accounts;

  DebitAccountHandler(Accounts accounts) {
    this.accounts = accounts;
  }

  @Override
  public Void handle(DebitAccount command, CommandContext context) {
    AccountId id = new AccountId(command.accountId());
    Account account =
        accounts
            .find(id)
            .orElseThrow(
                () ->
                    new EntityNotFoundException(
                        BankingErrorCode.ACCOUNT_NOT_FOUND,
                        "no account " + command.accountId()));
    account.debit(command.amountMinor(), command.reference());
    accounts.save(account);
    return null;
  }
}

package com.example.samples.s09.ticketing.application;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s09.ticketing.domain.TicketingErrorCode;
import com.example.samples.s09.ticketing.domain.Wallet;
import com.example.samples.s09.ticketing.domain.WalletId;
import com.example.samples.s09.ticketing.domain.Wallets;
import java.time.Clock;
import org.springframework.stereotype.Component;

/**
 * The compensation, and the reason this sample has a ledger.
 *
 * <p>Its reference is {@code refund-of:<the debit's reference>} — derived from the movement it makes good,
 * so it is stable across redeliveries and says what it is for. Two entries end up on the statement, a
 * debit and a credit, and both stay there. A rollback would have left one entry that never existed.
 */
@Component
class RefundWalletHandler implements CommandHandler<RefundWallet, Void> {

  private final Wallets wallets;
  private final TicketingProcess process;
  private final Clock clock;

  RefundWalletHandler(Wallets wallets, TicketingProcess process, Clock clock) {
    this.wallets = wallets;
    this.process = process;
    this.clock = clock;
  }

  @Override
  public Void handle(RefundWallet command, CommandContext context) {
    WalletId id = new WalletId(command.customerId());
    Wallet wallet =
        wallets
            .find(id)
            .orElseThrow(
                () ->
                    new EntityNotFoundException(
                        TicketingErrorCode.WALLET_NOT_FOUND,
                        "no wallet for " + command.customerId()));

    wallet.credit(
        "refund-of:" + command.debitReference(),
        command.amountMinor(),
        command.reason(),
        clock.instant());
    wallets.save(wallet);
    process.walletRefunded(command.orderId(), context);
    return null;
  }
}

package com.example.samples.s09.ticketing.application;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s09.ticketing.domain.DebitOutcome;
import com.example.samples.s09.ticketing.domain.TicketingErrorCode;
import com.example.samples.s09.ticketing.domain.Wallet;
import com.example.samples.s09.ticketing.domain.WalletId;
import com.example.samples.s09.ticketing.domain.Wallets;
import java.time.Clock;
import org.springframework.stereotype.Component;

/**
 * Take the money, and report back either the reference it moved under or why it did not move.
 *
 * <p><strong>The reference is derived, not minted.</strong> {@code ticket-debit:<orderId>} is the same
 * string on every redelivery of this effect, which is what makes the debit idempotent — the aggregate
 * recognises it and answers {@code ALREADY_APPLIED}. A random reference would make each redelivery a new
 * debit, and the customer would pay twice for one seat.
 *
 * <p>It is nonetheless <em>reported back and stored in the flow's state</em>, which looks redundant here
 * and is not: determinism is what makes the charge safe to repeat, and remembering is what makes it
 * possible to refund. Those are two different requirements that happen to coincide while there is exactly
 * one charge per order. A flow with two charges would have two references and no way to derive either.
 */
@Component
class ChargeWalletHandler implements CommandHandler<ChargeWallet, Void> {

  private final Wallets wallets;
  private final TicketingProcess process;
  private final Clock clock;

  ChargeWalletHandler(Wallets wallets, TicketingProcess process, Clock clock) {
    this.wallets = wallets;
    this.process = process;
    this.clock = clock;
  }

  @Override
  public Void handle(ChargeWallet command, CommandContext context) {
    WalletId id = new WalletId(command.customerId());
    Wallet wallet =
        wallets
            .find(id)
            .orElseThrow(
                () ->
                    new EntityNotFoundException(
                        TicketingErrorCode.WALLET_NOT_FOUND,
                        "no wallet for " + command.customerId()));

    String reference = debitReference(command.orderId());
    DebitOutcome outcome =
        wallet.debit(
            reference, command.amountMinor(), "ticket " + command.orderId(), clock.instant());
    wallets.save(wallet);

    if (outcome == DebitOutcome.INSUFFICIENT_FUNDS) {
      process.walletDeclined(command.orderId(), "insufficient funds", context);
    } else {
      process.walletCharged(command.orderId(), reference, context);
    }
    return null;
  }

  static String debitReference(String orderId) {
    return "ticket-debit:" + orderId;
  }
}

package com.example.samples.s27.customer.application;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.aipersimmon.ddd.inbox.Inbox;
import com.example.samples.s27.customer.domain.CustomerId;
import org.springframework.stereotype.Component;

/**
 * Ask the inbox first, and do the work only once.
 *
 * <p>Both writes are in the command's transaction, which is what the {@code Inbox} contract requires: if the
 * processing fails, the key rolls back with it and a redelivery gets another chance. The reverse arrangement —
 * key committed first — turns every failure into a permanently skipped message.
 *
 * @return whether this call did the work ({@code false} means it was a redelivery)
 */
@Component
class AbsorbMarketingSignalHandler implements CommandHandler<AbsorbMarketingSignal, Boolean> {

  private final Inbox inbox;
  private final MarketingConsents consents;

  AbsorbMarketingSignalHandler(Inbox inbox, MarketingConsents consents) {
    this.inbox = inbox;
    this.consents = consents;
  }

  @Override
  public Boolean handle(AbsorbMarketingSignal command, CommandContext context) {
    if (inbox.alreadyProcessed(command.source(), command.messageKey())) {
      return false;
    }
    consents.grant(new CustomerId(command.customerId()), command.note());
    return true;
  }
}

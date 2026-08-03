package com.example.samples.s03.ordering.application;

import com.aipersimmon.ddd.application.DomainEventHandler;
import com.example.samples.s03.ordering.domain.OrderPlaced;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * A reaction that must not happen unless the write committed: telling a customer about an order that
 * rolled back is worse than telling them nothing.
 *
 * <p>{@code AFTER_COMMIT} buys that, and costs something the sample makes explicit: this listener runs
 * outside the transaction, so if it throws, the order stays committed and the notification is simply
 * gone. Nothing retries it, nothing records that it was owed. When the reaction cannot be allowed to
 * vanish, an in-process event is the wrong mechanism — see the document, and S4.
 */
@Component
@DomainEventHandler
class NotifyCustomer {

  private final CustomerNotifier notifier;

  NotifyCustomer(CustomerNotifier notifier) {
    this.notifier = notifier;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  void on(OrderPlaced event) {
    notifier.orderConfirmedTo(event.customerId(), event.orderId().value());
  }
}

package com.example.samples.s23.billing.domain;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;

/**
 * An invoice for an order.
 *
 * <p>{@code orderId} is a plain string, on purpose: it is ordering's identity, and importing
 * {@code OrderId} would make billing depend on ordering's domain. An id that crosses a context boundary
 * arrives as a value and is trusted as far as the flow that carried it — which is also why billing's table has
 * no foreign key to ordering's.
 */
@AggregateRoot
public final class Invoice extends AbstractAggregateRoot<InvoiceId> {

  private final InvoiceId id;
  private final String orderId;
  private final long amountMinor;

  private Invoice(InvoiceId id, String orderId, long amountMinor) {
    this.id = id;
    this.orderId = orderId;
    this.amountMinor = amountMinor;
  }

  public static Invoice raise(InvoiceId id, String orderId, long amountMinor) {
    Invoice invoice = new Invoice(id, orderId, amountMinor);
    invoice.checkInvariant(new InvoiceIsForMoney(amountMinor));
    return invoice;
  }

  public static Invoice reconstitute(
      InvoiceId id, String orderId, long amountMinor, long version) {
    Invoice invoice = new Invoice(id, orderId, amountMinor);
    invoice.restoreVersion(version);
    return invoice;
  }

  @Override
  public InvoiceId id() {
    return id;
  }

  public String orderId() {
    return orderId;
  }

  public long amountMinor() {
    return amountMinor;
  }
}

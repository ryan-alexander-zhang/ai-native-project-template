package com.example.samples.s23.billing.application;

import com.aipersimmon.ddd.core.id.IdGenerator;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s23.billing.domain.Invoice;
import com.example.samples.s23.billing.domain.InvoiceId;
import com.example.samples.s23.billing.domain.Invoices;
import org.springframework.stereotype.Component;

/** Raises an invoice. Billing's whole behaviour, because its job here is to own a migration set. */
@Component
class RaiseInvoiceHandler implements CommandHandler<RaiseInvoice, String> {

  private final Invoices invoices;
  private final IdGenerator idGenerator;

  RaiseInvoiceHandler(Invoices invoices, IdGenerator idGenerator) {
    this.invoices = invoices;
    this.idGenerator = idGenerator;
  }

  @Override
  public String handle(RaiseInvoice command, CommandContext context) {
    InvoiceId id = new InvoiceId(idGenerator.newId());
    invoices.save(Invoice.raise(id, command.orderId(), command.amountMinor()));
    return id.value();
  }
}

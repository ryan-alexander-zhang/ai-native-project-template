package com.example.samples.s23.billing.application;

import com.aipersimmon.ddd.cqrs.QueryHandler;
import com.example.samples.s23.billing.domain.InvoiceId;
import com.example.samples.s23.billing.domain.Invoices;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Projects one invoice, behind the bus rather than in the endpoint that used to hold the port. */
@Component
class FindInvoiceHandler implements QueryHandler<FindInvoice, Optional<InvoiceView>> {

  private final Invoices invoices;

  FindInvoiceHandler(Invoices invoices) {
    this.invoices = invoices;
  }

  @Override
  public Optional<InvoiceView> handle(FindInvoice query) {
    return invoices
        .find(new InvoiceId(query.invoiceId()))
        .map(
            invoice ->
                new InvoiceView(invoice.id().value(), invoice.orderId(), invoice.amountMinor()));
  }
}

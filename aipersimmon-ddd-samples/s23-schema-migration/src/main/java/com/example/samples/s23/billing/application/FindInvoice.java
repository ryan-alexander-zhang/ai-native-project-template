package com.example.samples.s23.billing.application;

import com.aipersimmon.ddd.cqrs.Query;
import java.util.Optional;

/** Ask for one invoice. Empty when there is no such invoice. */
public record FindInvoice(String invoiceId) implements Query<Optional<InvoiceView>> {}

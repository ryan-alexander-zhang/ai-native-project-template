package com.example.samples.s23.billing.domain;

import com.aipersimmon.ddd.core.annotation.Repository;
import java.util.Optional;

/** The write port. */
@Repository
public interface Invoices {

  void save(Invoice invoice);

  Optional<Invoice> find(InvoiceId id);
}

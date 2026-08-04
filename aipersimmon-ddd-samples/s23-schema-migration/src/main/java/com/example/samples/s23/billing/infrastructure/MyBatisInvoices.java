package com.example.samples.s23.billing.infrastructure;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.persistence.mybatisplus.MybatisPlusAggregateRepository;
import com.example.samples.s23.billing.domain.Invoice;
import com.example.samples.s23.billing.domain.InvoiceId;
import com.example.samples.s23.billing.domain.Invoices;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** The write path. */
@Repository
class MyBatisInvoices extends MybatisPlusAggregateRepository<Invoice, InvoiceRow>
    implements Invoices {

  private final InvoiceMapper mapper;

  MyBatisInvoices(InvoiceMapper mapper, DomainEvents domainEvents) {
    super(mapper, domainEvents);
    this.mapper = mapper;
  }

  @Override
  public void save(Invoice invoice) {
    saveAggregate(invoice);
  }

  @Override
  public Optional<Invoice> find(InvoiceId id) {
    InvoiceRow row = mapper.selectById(id.value());
    return row == null
        ? Optional.empty()
        : Optional.of(
            Invoice.reconstitute(id, row.getOrderId(), row.getAmountMinor(), row.getVersion()));
  }

  @Override
  protected InvoiceRow toRow(Invoice invoice) {
    InvoiceRow row = new InvoiceRow();
    row.setId(invoice.id().value());
    row.setOrderId(invoice.orderId());
    row.setAmountMinor(invoice.amountMinor());
    return row;
  }
}

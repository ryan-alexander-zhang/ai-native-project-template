package com.example.samples.s07.payments.infrastructure;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.persistence.mybatisplus.MybatisPlusAggregateRepository;
import com.example.samples.s07.payments.domain.Payment;
import com.example.samples.s07.payments.domain.PaymentId;
import com.example.samples.s07.payments.domain.PaymentStatus;
import com.example.samples.s07.payments.domain.Payments;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * The write path. Nothing here knows about the gateway, and nothing here knows about the outbox — the
 * outbox row is written by the framework's {@code IntegrationEvents} implementation in the same
 * transaction this save runs in, which is why the two commit together with neither side coordinating.
 */
@Repository
class MyBatisPayments extends MybatisPlusAggregateRepository<Payment, PaymentRow>
    implements Payments {

  private final PaymentMapper mapper;

  MyBatisPayments(PaymentMapper mapper, DomainEvents domainEvents) {
    super(mapper, domainEvents);
    this.mapper = mapper;
  }

  @Override
  public void save(Payment payment) {
    saveAggregate(payment);
  }

  @Override
  public Optional<Payment> find(PaymentId id) {
    PaymentRow row = mapper.selectById(id.value());
    if (row == null) {
      return Optional.empty();
    }
    return Optional.of(
        Payment.reconstitute(
            id,
            row.getOrderRef(),
            row.getAmountMinor(),
            row.getRequestedAt(),
            PaymentStatus.valueOf(row.getStatus()),
            row.getGatewayRef(),
            row.getReviewReason(),
            row.getVersion()));
  }

  /**
   * The status is stored as its name, not its ordinal.
   *
   * <p>An ordinal is smaller and it is a trap: it binds the persisted meaning of every existing row to
   * the declaration order of an enum, so inserting a member in the middle silently rewrites history. A
   * name costs bytes and survives editing.
   */
  @Override
  protected PaymentRow toRow(Payment payment) {
    PaymentRow row = new PaymentRow();
    row.setId(payment.id().value());
    row.setOrderRef(payment.orderRef());
    row.setAmountMinor(payment.amountMinor());
    row.setRequestedAt(payment.requestedAt());
    row.setStatus(payment.status().name());
    row.setGatewayRef(payment.gatewayRef());
    row.setReviewReason(payment.reviewReason());
    return row;
  }
}

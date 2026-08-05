package com.example.samples.s25.refunds.infrastructure;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.persistence.mybatisplus.MybatisPlusAggregateRepository;
import com.example.samples.s25.refunds.domain.Refund;
import com.example.samples.s25.refunds.domain.RefundId;
import com.example.samples.s25.refunds.domain.RefundIds;
import com.example.samples.s25.refunds.domain.Refunds;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * The refund's write path, over a table the library did not design — and it works, with two accommodations.
 *
 * <p><strong>The id is supplied, not assigned.</strong> {@link #reserve} takes it from the table's own sequence, so the
 * insert can name it. Without that the library refuses the insert, correctly, because a row with no primary key would
 * make an update match every row of the table.
 *
 * <p><strong>{@code toRow} maps only what the aggregate owns.</strong> {@code created_at} and {@code updated_at} are the
 * monolith's, and they are absent from {@code RefundRow} rather than present-and-null — see that class. What the
 * aggregate does own it maps in full, including the nulls, which is what {@code ClearedColumns} is for.
 */
@Repository
class MyBatisRefunds extends MybatisPlusAggregateRepository<Refund, RefundRow>
    implements Refunds, RefundIds {

  private final RefundMapper mapper;

  MyBatisRefunds(RefundMapper mapper, DomainEvents domainEvents) {
    super(mapper, domainEvents);
    this.mapper = mapper;
  }

  @Override
  public RefundId reserve() {
    return new RefundId(mapper.nextId());
  }

  @Override
  public void save(Refund refund) {
    saveAggregate(refund);
  }

  @Override
  public boolean hasOpenRefund(long orderId) {
    return mapper.hasOpenRefund(orderId);
  }

  @Override
  public Optional<Refund> find(RefundId id) {
    RefundRow row = mapper.selectById(id.value());
    if (row == null) {
      return Optional.empty();
    }
    return Optional.of(
        Refund.reconstitute(
            id,
            row.getOrderId(),
            row.getAmountCents(),
            row.getReason(),
            row.getPublicId(),
            row.getState(),
            row.getApprovedBy(),
            row.getVersion()));
  }

  @Override
  protected RefundRow toRow(Refund refund) {
    RefundRow row = new RefundRow();
    row.setId(refund.id().value());
    row.setOrderId(refund.orderId());
    row.setAmountCents(refund.amountCents());
    row.setReason(refund.reason().orElse(null));
    row.setPublicId(refund.publicId());
    row.setState(refund.state().name());
    row.setApprovedBy(refund.approvedBy().orElse(null));
    // No created_at / updated_at: they belong to the monolith. See RefundRow.
    return row;
  }
}

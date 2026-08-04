package com.example.samples.s09.ticketing.infrastructure;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.persistence.mybatisplus.MybatisPlusAggregateRepository;
import com.example.samples.s09.ticketing.domain.OrderStatus;
import com.example.samples.s09.ticketing.domain.TicketOrder;
import com.example.samples.s09.ticketing.domain.TicketOrderId;
import com.example.samples.s09.ticketing.domain.TicketOrders;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** The order's write path. */
@Repository
class MyBatisTicketOrders extends MybatisPlusAggregateRepository<TicketOrder, TicketOrderRow>
    implements TicketOrders {

  private final TicketOrderMapper mapper;

  MyBatisTicketOrders(TicketOrderMapper mapper, DomainEvents domainEvents) {
    super(mapper, domainEvents);
    this.mapper = mapper;
  }

  @Override
  public void save(TicketOrder order) {
    saveAggregate(order);
  }

  @Override
  public Optional<TicketOrder> find(TicketOrderId id) {
    TicketOrderRow row = mapper.selectById(id.value());
    if (row == null) {
      return Optional.empty();
    }
    return Optional.of(
        TicketOrder.reconstitute(
            id,
            row.getCustomerId(),
            row.getSeatClass(),
            row.getAmountMinor(),
            OrderStatus.valueOf(row.getStatus()),
            row.getCancelReason(),
            row.getVersion()));
  }

  @Override
  protected TicketOrderRow toRow(TicketOrder order) {
    TicketOrderRow row = new TicketOrderRow();
    row.setId(order.id().value());
    row.setCustomerId(order.customerId());
    row.setSeatClass(order.seatClass());
    row.setAmountMinor(order.amountMinor());
    row.setStatus(order.status().name());
    row.setCancelReason(order.cancelReason());
    return row;
  }
}

package com.example.samples.s03.ordering.infrastructure;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.persistence.mybatisplus.MybatisPlusAggregateRepository;
import com.example.samples.s03.ordering.domain.Order;
import com.example.samples.s03.ordering.domain.OrderId;
import com.example.samples.s03.ordering.domain.Orders;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * The publisher, whether it looks like one or not: {@code saveAggregate}'s last step is
 * {@code domainEvents.publishAndClear(aggregate)} — same thread, same transaction.
 */
@Repository
class MyBatisOrders extends MybatisPlusAggregateRepository<Order, OrderRow> implements Orders {

  private final OrderMapper mapper;

  MyBatisOrders(OrderMapper mapper, DomainEvents domainEvents) {
    super(mapper, domainEvents);
    this.mapper = mapper;
  }

  @Override
  public void save(Order order) {
    saveAggregate(order);
  }

  @Override
  public Optional<Order> findById(OrderId id) {
    OrderRow row = mapper.selectById(id.value());
    if (row == null) {
      return Optional.empty();
    }
    return Optional.of(
        Order.reconstitute(
            new OrderId(row.getId()),
            row.getCustomerId(),
            row.getAmountCents(),
            row.getReviewReason(),
            row.getVersion()));
  }

  @Override
  protected OrderRow toRow(Order order) {
    OrderRow row = new OrderRow();
    row.setId(order.id().value());
    row.setCustomerId(order.customerId());
    row.setAmountCents(order.amountCents());
    row.setReviewReason(order.reviewReason());
    return row;
  }
}

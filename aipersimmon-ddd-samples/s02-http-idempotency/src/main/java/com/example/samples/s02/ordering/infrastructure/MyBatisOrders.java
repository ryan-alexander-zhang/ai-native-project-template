package com.example.samples.s02.ordering.infrastructure;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.persistence.mybatisplus.MybatisPlusAggregateRepository;
import com.example.samples.s02.ordering.domain.ClientReference;
import com.example.samples.s02.ordering.domain.Order;
import com.example.samples.s02.ordering.domain.OrderId;
import com.example.samples.s02.ordering.domain.Orders;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * The write side. The insert can fail on the {@code client_reference} UNIQUE index; the command bus's
 * translation interceptor turns Spring's {@code DuplicateKeyException} into
 * {@code DuplicateEntityException}, which the web layer renders as 409. Nothing here catches it.
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
            new ClientReference(row.getClientReference()),
            row.getAmountCents(),
            row.getVersion()));
  }

  @Override
  protected OrderRow toRow(Order order) {
    OrderRow row = new OrderRow();
    row.setId(order.id().value());
    row.setClientReference(order.clientReference().value());
    row.setAmountCents(order.amountCents());
    return row;
  }
}

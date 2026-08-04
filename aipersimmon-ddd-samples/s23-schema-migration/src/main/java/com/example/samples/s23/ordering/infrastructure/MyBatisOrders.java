package com.example.samples.s23.ordering.infrastructure;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.persistence.mybatisplus.MybatisPlusAggregateRepository;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.samples.s23.ordering.domain.Handling;
import com.example.samples.s23.ordering.domain.Order;
import com.example.samples.s23.ordering.domain.OrderId;
import com.example.samples.s23.ordering.domain.Orders;
import com.example.samples.s23.ordering.domain.ShipTo;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** The write path. */
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
  public Optional<Order> find(OrderId id) {
    OrderRow row = mapper.selectById(id.value());
    if (row == null) {
      return Optional.empty();
    }
    return Optional.of(
        Order.reconstitute(
            id,
            row.getCustomerId(),
            row.getSku(),
            row.getQuantity(),
            new ShipTo(row.getShipToStreet(), row.getShipToCity()),
            row.getHandling() == null ? null : Handling.valueOf(row.getHandling()),
            row.getVersion()));
  }

  /**
   * The undecided page, ordered by id so the pages are stable.
   *
   * <p>Ordered by something rather than nothing, because an unordered LIMIT over a table that is being
   * written to can return the same row twice and never return another — and a backfill loop that trusts
   * "returned zero" as its exit condition would then stop with rows left. The partial index V4 added is what
   * keeps this cheap as the undecided population shrinks.
   */
  @Override
  public List<OrderId> undecidedHandling(int limit) {
    return mapper
        .selectList(
            new LambdaQueryWrapper<OrderRow>()
                .select(OrderRow::getId)
                .isNull(OrderRow::getHandling)
                .orderByAsc(OrderRow::getId)
                .last("LIMIT " + limit))
        .stream()
        .map(row -> new OrderId(row.getId()))
        .toList();
  }

  @Override
  protected OrderRow toRow(Order order) {
    OrderRow row = new OrderRow();
    row.setId(order.id().value());
    row.setCustomerId(order.customerId());
    row.setSku(order.sku());
    row.setQuantity(order.quantity());
    row.setShipToStreet(order.shipTo().street());
    row.setShipToCity(order.shipTo().city());
    row.setHandling(order.handling().map(Enum::name).orElse(null));
    return row;
  }
}

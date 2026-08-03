package com.example.samples.s17.ordering.infrastructure;

import com.example.samples.s17.ordering.domain.Order;
import org.springframework.stereotype.Component;

/**
 * A deliberate counterexample, kept because a test proves what it loses.
 *
 * <p>It writes the root the way most people write it — {@code updateById} with the mapped entity — and
 * differs from {@link MyBatisOrders} in exactly one respect, so the comparison isolates one variable.
 * The optimistic lock still applies (the interceptor is global), the version still moves, and any
 * domain events would still publish. What it drops is the assignment for every null field, because
 * MyBatis-Plus's default field strategy reads null as "I am not saying anything about this column".
 *
 * <p>For a partial update that is the right default. For saving an aggregate it is wrong, and wrong
 * quietly: the command is accepted, the version advances, downstream is told the change happened, and
 * the old value is still in the database when the aggregate is next loaded.
 */
@Component
public class NaiveOrderWriter {

  private final OrderMapper mapper;

  NaiveOrderWriter(OrderMapper mapper) {
    this.mapper = mapper;
  }

  /** @return rows updated, straight from the mapper */
  public int save(Order order) {
    OrderRow row = new OrderRow();
    row.setId(order.id().value());
    row.setCustomerId(order.customerId());
    row.setStatus(order.status().name());
    row.setNote(order.note());
    row.setShippingAddress(order.shippingAddress());
    row.setTotalCurrency(order.total().currency());
    row.setTotalAmountCents(order.total().amountCents());
    row.setVersion(order.version());
    return mapper.updateById(row);
  }
}

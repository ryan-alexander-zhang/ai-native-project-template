package com.example.samples.s26.catalog.infrastructure;

import com.example.samples.s26.catalog.application.OrderLines;
import com.example.samples.s26.catalog.domain.Sku;
import java.time.Instant;
import org.springframework.stereotype.Repository;

/** Appends a sold line. One insert, no read. */
@Repository
class MyBatisOrderLines implements OrderLines {

  private final OrderLineMapper mapper;

  MyBatisOrderLines(OrderLineMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public void append(String id, Sku sku, int quantity, Instant placedAt) {
    OrderLineRow row = new OrderLineRow();
    row.setId(id);
    row.setSku(sku.value());
    row.setQuantity(quantity);
    row.setPlacedAt(placedAt);
    mapper.insert(row);
  }
}

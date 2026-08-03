package com.example.samples.s19.ordering.application;

import com.aipersimmon.ddd.core.exception.DomainException;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandPrecheck;
import com.example.samples.s19.ordering.domain.OrderingErrorCode;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * A second precheck on the same command. All of them run, in bean order, and the first refusal wins —
 * which is why the {@code @Order} above is part of the contract rather than decoration.
 */
@Component
@Order(20)
class WarehouseMustBeOpen implements CommandPrecheck<PlaceOrder> {

  private final WarehouseCalendar calendar;

  WarehouseMustBeOpen(WarehouseCalendar calendar) {
    this.calendar = calendar;
  }

  @Override
  public void check(PlaceOrder command, CommandContext context) {
    if (!calendar.acceptingOrders()) {
      throw new DomainException(
          OrderingErrorCode.WAREHOUSE_CLOSED, "the warehouse is not accepting orders");
    }
  }
}

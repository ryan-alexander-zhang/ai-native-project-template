package com.example.samples.s19.ordering.infrastructure;

import com.example.samples.s19.ordering.application.WarehouseCalendar;
import org.springframework.stereotype.Component;

/** The production calendar. Tests replace it to close the warehouse. */
@Component
class AlwaysOpenWarehouse implements WarehouseCalendar {

  @Override
  public boolean acceptingOrders() {
    return true;
  }
}

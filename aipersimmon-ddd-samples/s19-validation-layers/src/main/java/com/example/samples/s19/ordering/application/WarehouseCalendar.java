package com.example.samples.s19.ordering.application;

/** A second advisory source, so the sample can show what happens when two prechecks both refuse. */
public interface WarehouseCalendar {

  boolean acceptingOrders();
}

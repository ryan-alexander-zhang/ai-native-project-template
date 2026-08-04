package com.example.samples.s09.ticketing.domain;

import com.aipersimmon.ddd.core.annotation.Repository;
import java.util.Optional;

/** The write port for orders. */
@Repository
public interface TicketOrders {

  void save(TicketOrder order);

  Optional<TicketOrder> find(TicketOrderId id);
}

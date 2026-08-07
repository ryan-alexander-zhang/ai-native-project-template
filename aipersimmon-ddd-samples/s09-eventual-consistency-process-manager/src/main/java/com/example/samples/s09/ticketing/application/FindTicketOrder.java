package com.example.samples.s09.ticketing.application;

import com.aipersimmon.ddd.cqrs.Query;
import java.util.Optional;

/** Ask for one ticket order. Empty when there is no such order. */
public record FindTicketOrder(String orderId) implements Query<Optional<TicketOrderView>> {}

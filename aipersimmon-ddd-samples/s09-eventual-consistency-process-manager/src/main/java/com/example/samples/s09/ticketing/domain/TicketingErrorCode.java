package com.example.samples.s09.ticketing.domain;

import com.aipersimmon.ddd.core.error.ErrorCategory;
import com.aipersimmon.ddd.core.error.ErrorCode;

/**
 * This context's error codes — and note how few there are. Almost everything that can go wrong in this
 * flow is a business <em>outcome</em> (sold out, insufficient funds, already applied) carried back as a
 * return value for the coordinator to act on. An error code is reserved for the cases where the flow
 * itself is wrong: it asked for something the aggregate must refuse.
 */
public enum TicketingErrorCode implements ErrorCode {

  AMOUNT_NOT_POSITIVE("ticketing.amount-not-positive", ErrorCategory.DOMAIN_RULE),

  /** The flow tried to ticket an order that had already been cancelled. */
  ORDER_ALREADY_CANCELLED("ticketing.order-already-cancelled", ErrorCategory.DOMAIN_RULE),

  /** The flow tried to compensate past the point of no return. */
  TICKET_ALREADY_ISSUED("ticketing.ticket-already-issued", ErrorCategory.DOMAIN_RULE),

  ORDER_NOT_FOUND("ticketing.order-not-found", ErrorCategory.NOT_FOUND),

  SEAT_CLASS_NOT_FOUND("ticketing.seat-class-not-found", ErrorCategory.NOT_FOUND),

  WALLET_NOT_FOUND("ticketing.wallet-not-found", ErrorCategory.NOT_FOUND);

  private final String code;
  private final ErrorCategory category;

  TicketingErrorCode(String code, ErrorCategory category) {
    this.code = code;
    this.category = category;
  }

  @Override
  public String code() {
    return code;
  }

  @Override
  public ErrorCategory category() {
    return category;
  }
}

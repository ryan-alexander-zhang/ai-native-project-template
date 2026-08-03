package com.example.samples.s02.ordering.interfaces;

import com.example.samples.s02.ordering.application.OrderView;

/** The HTTP response body: the resource, with no envelope. */
record OrderResponse(String id, String clientReference, long amountCents) {

  static OrderResponse of(OrderView view) {
    return new OrderResponse(view.id(), view.clientReference(), view.amountCents());
  }
}

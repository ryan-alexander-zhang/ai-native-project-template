package com.example.samples.s01.ordering.adapter;

import com.example.samples.s01.ordering.application.OrderView;
import java.util.List;

/**
 * The HTTP response body: the resource itself, with no success envelope around it. The library takes
 * that stance deliberately — a success returns the resource with the right status code, and only a
 * failure has a wrapper, the RFC 9457 problem document.
 */
record OrderResponse(String id, String customerId, String status, List<Line> lines) {

  record Line(String sku, int quantity) {}

  static OrderResponse of(OrderView view) {
    return new OrderResponse(
        view.id(),
        view.customerId(),
        view.status(),
        view.lines().stream().map(line -> new Line(line.sku(), line.quantity())).toList());
  }
}

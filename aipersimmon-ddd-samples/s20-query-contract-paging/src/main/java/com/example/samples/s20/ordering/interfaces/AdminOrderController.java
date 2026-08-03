package com.example.samples.s20.ordering.interfaces;

import com.aipersimmon.ddd.cqrs.QueryBus;
import com.aipersimmon.ddd.cqrs.page.Page;
import com.example.samples.s20.ordering.application.BrowseOrdersWithTotals;
import com.example.samples.s20.ordering.application.OrderFilter;
import com.example.samples.s20.ordering.application.OrderSort;
import com.example.samples.s20.ordering.application.OrderSummary;
import com.example.samples.s20.ordering.application.PageRequest;
import com.example.samples.s20.ordering.domain.OrderStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one screen that genuinely reads the total — a back-office list with "1,247 orders" at the top.
 *
 * <p>It is a separate endpoint, not a flag, because it returns a different shape and costs a second
 * statement. Naming it {@code /admin/orders} rather than {@code /orders?withTotals=true} makes the
 * price a routing decision somebody has to make on purpose, instead of a query parameter every
 * client discovers and then always sends.
 */
@RestController
@RequestMapping("/admin/orders")
class AdminOrderController {

  private final QueryBus queryBus;

  AdminOrderController(QueryBus queryBus) {
    this.queryBus = queryBus;
  }

  @GetMapping
  Page<OrderSummary> browse(
      @RequestParam(required = false) String customerId,
      @RequestParam(required = false) OrderStatus status,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "NEWEST_FIRST") OrderSort sort,
      @RequestParam(defaultValue = "" + PageRequest.DEFAULT_SIZE) int size) {
    return queryBus.ask(
        new BrowseOrdersWithTotals(
            new PageRequest(
                new OrderFilter(customerId, status),
                sort,
                size,
                OrderController.cursorOf(cursor))));
  }
}

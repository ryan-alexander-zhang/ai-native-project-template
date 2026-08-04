package com.example.samples.s12.ordering.application;

import java.util.List;
import java.util.Optional;

/**
 * Reading the projection.
 *
 * <p>No filters, one ordering, one limit. Paging a list properly — cursors, total ordering, what offset
 * loses — is S20's subject and is not re-taught here; a projection changes nothing about it. What S12 adds
 * is only that the rows come from a derived table instead of the write model.
 */
public interface OrderListQueries {

  List<OrderListItem> recentFor(String customerId, int limit);

  Optional<OrderListItem> find(String orderId);
}

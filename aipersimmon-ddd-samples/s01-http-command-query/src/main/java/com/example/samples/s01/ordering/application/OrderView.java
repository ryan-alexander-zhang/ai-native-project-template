package com.example.samples.s01.ordering.application;

import com.aipersimmon.ddd.cqrs.ReadModel;
import java.util.List;

/**
 * What a read of one order answers. Shaped for the query, not for the aggregate — which is why it is
 * a flat record and not an {@code Order}.
 */
@ReadModel
public record OrderView(String id, String customerId, String status, List<LineView> lines) {

  public record LineView(String sku, int quantity) {}
}

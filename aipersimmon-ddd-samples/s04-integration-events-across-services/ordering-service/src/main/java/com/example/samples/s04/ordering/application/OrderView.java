package com.example.samples.s04.ordering.application;

import com.aipersimmon.ddd.cqrs.ReadModel;
import java.util.List;

/**
 * What a caller gets back when it asks for an order: the fields the answer needs, and nothing the
 * aggregate happens to hold as well.
 *
 * <p>The field names are the JSON, so a rename here is an API change and is visible as one —
 * whereas the hand-built response map this replaced put the same coupling in a place no signature
 * mentioned.
 */
@ReadModel
public record OrderView(String id, String customerId, List<LineView> lines) {

  /** One line of the order, as the answer reports it. */
  public record LineView(String sku, int quantity) {}
}

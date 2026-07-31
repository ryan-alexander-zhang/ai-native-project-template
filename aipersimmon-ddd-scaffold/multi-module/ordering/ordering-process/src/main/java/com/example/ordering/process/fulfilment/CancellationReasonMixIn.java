package com.example.ordering.process.fulfilment;

import com.example.ordering.domain.order.CancellationReason;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * The Jackson mix-in for {@link CancellationReason} — the polymorphism declaration the domain type
 * must not carry, living here in the process module instead. Registered through the serialization
 * catalog's {@code mixIn(...)}, it applies only to the process manager's codec-private mapper:
 * never to the application's shared {@code ObjectMapper}, and never to the domain type itself.
 *
 * <p>The discriminators are wire contract, like a catalog entry's logical type: renaming a variant
 * class must not change them, and changing one of these strings orphans every persisted effect that
 * carries it.
 *
 * <p>All four sealed variants are mapped, although the fulfilment flow only dispatches two — the
 * codec's job is to encode the <em>type</em> faithfully; which reasons the flow may dispatch is the
 * definition's business, enforced where the reasons are constructed. The framework checks this
 * completeness at startup: a sealed target whose mix-in misses a variant refuses to boot, because
 * Jackson would encode the unmapped one under a fallback class name that nothing can decode — a
 * poison effect discovered in the relay instead of a wiring error discovered here.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
  @JsonSubTypes.Type(
      value = CancellationReason.CustomerRequested.class,
      name = "CUSTOMER_REQUESTED"),
  @JsonSubTypes.Type(
      value = CancellationReason.InventoryUnavailable.class,
      name = "INVENTORY_UNAVAILABLE"),
  @JsonSubTypes.Type(
      value = CancellationReason.PaymentDeclinedAfterStockReleased.class,
      name = "PAYMENT_DECLINED_AFTER_STOCK_RELEASED"),
  @JsonSubTypes.Type(value = CancellationReason.ReviewRejected.class, name = "REVIEW_REJECTED")
})
interface CancellationReasonMixIn {}

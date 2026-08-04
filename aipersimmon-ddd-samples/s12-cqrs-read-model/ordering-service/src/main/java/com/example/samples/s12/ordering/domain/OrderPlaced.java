package com.example.samples.s12.ordering.domain;

import com.aipersimmon.ddd.core.event.DomainEvent;

/**
 * An order was placed.
 *
 * <p><strong>It carries the two identities and nothing else, deliberately.</strong> The obvious
 * alternative is to carry every value the projection needs — customer, total, lines, timestamps — so the
 * subscriber never touches the database. That is faster and it is the design that drifts: the projection
 * then knows how to apply a delta and not how to compute a row, so it can never repair itself, and a
 * rebuild has to be written a second time against different inputs. Here the projection re-reads the
 * order, so <em>incremental update and full rebuild are literally the same code path</em>, and that is
 * what makes the projection disposable.
 *
 * <p>The cost is one read per event, inside a transaction that has the row in its own buffer cache
 * anyway. The trade is named rather than assumed.
 */
public record OrderPlaced(OrderId orderId, String customerId) implements DomainEvent {}

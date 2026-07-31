/**
 * The Stock aggregate: its root {@link com.example.inventory.domain.stock.Stock} (keyed by {@link
 * com.example.inventory.domain.stock.Sku}), the reservation rule, and the {@code Stocks} repository
 * port.
 *
 * <h2>Why these aggregates record no domain events</h2>
 *
 * <p>Ordering's aggregates register domain events and this context's never do — that asymmetry is a
 * decision, not an omission (issue-00150). A domain event exists for subscribers <em>inside</em>
 * the context: ordering has them (its fulfilment trigger reacts to {@code OrderReadyForFulfilment}
 * becoming true), so its aggregates announce. Inventory has none — nothing in this context reacts
 * to "stock was reserved" except the very handler that reserved it, which then assembles the
 * cross-context integration event directly. Recording a domain event here would add a hop
 * (aggregate → drain → subscriber → integration event) whose only subscriber restates what the
 * handler already knows, to no reader's benefit.
 *
 * <p>The moment this context grows an internal reaction — a reorder-point policy watching
 * depletion, say — the standard drain path is the way to add it: register the event in the
 * aggregate, let the repository's save drain and publish it, subscribe in the application layer.
 * The two contexts then converge; until then, the simpler shape is the honest one.
 */
package com.example.inventory.domain.stock;

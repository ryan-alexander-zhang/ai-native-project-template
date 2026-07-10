/**
 * How-to for a CQRS command pipeline and read model: the write side —
 * {@link com.example.howto.PlaceOrder} dispatched by the command bus to
 * {@link com.example.howto.PlaceOrderHandler}, which persists the
 * {@link com.example.howto.Order} and registers it for event draining — and the
 * read side — {@link com.example.howto.OrderSummaryProjection} building the
 * {@link com.example.howto.OrderSummary} read model from
 * {@link com.example.howto.OrderPlaced}, queried back through
 * {@link com.example.howto.FindOrderSummary}.
 */
package com.example.howto;

/**
 * What ordering publishes: one event.
 *
 * <p>Deliberately smaller than the coupons contract, and the asymmetry is the point — a context publishes what others
 * need, not a projection of itself. Nobody asks ordering a synchronous question, so there is no port here; nobody needs
 * to hold an order id as a typed value, so there is no published identifier. Both would be added the day something needs
 * them, which is a decision worth taking then rather than in advance.
 */
package com.example.samples.s24.ordering.api;

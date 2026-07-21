/**
 * The inventory context's published contract for other bounded contexts. It has two shapes: thin
 * integration <em>events</em> ({@link com.example.inventory.api.StockReserved}, {@link
 * com.example.inventory.api.StockReservationFailed}) that propagate state changes asynchronously,
 * and a synchronous Open Host Service ({@link com.example.inventory.api.StockAvailabilityApi})
 * other contexts call to read inventory's current state at decision time. Both carry ids and
 * minimal data rather than internal domain types.
 */
package com.example.inventory.api;

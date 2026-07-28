/**
 * Persistence adapters for the inventory context's two aggregates, behind the domain's {@code
 * Stocks} and {@code Reservations} ports. Both are version-checked on write, which is what keeps
 * concurrent reservations of one SKU from overselling it.
 */
package com.example.inventory.infrastructure.persistence;

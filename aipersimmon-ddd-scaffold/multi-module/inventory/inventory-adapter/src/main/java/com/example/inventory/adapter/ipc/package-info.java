/**
 * Inbound adapter that fronts the inventory context's synchronous Open Host Service ({@code
 * StockAvailabilityApi}) in-process — the query-side sibling of a REST controller, translating the
 * published contract into an application query. Replaced by an HTTP endpoint when the context
 * becomes its own service.
 */
package com.example.inventory.adapter.ipc;

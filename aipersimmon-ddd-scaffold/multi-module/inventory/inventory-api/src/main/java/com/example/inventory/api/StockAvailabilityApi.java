package com.example.inventory.api;

/**
 * The inventory context's synchronous Open Host Service: it answers, right now, which
 * of a set of SKUs it can currently offer. This is the published <em>query</em>
 * contract other contexts call when they need inventory's current state at decision
 * time — the synchronous counterpart to the {@link StockReserved} /
 * {@link StockReservationFailed} integration events, which propagate a
 * <em>state change</em> asynchronously.
 *
 * <p>It is transport-neutral. In the modular monolith it is a plain in-process bean
 * (the {@code inventory-adapter} ipc implementation); once inventory becomes its own
 * service the same interface is fronted by an HTTP endpoint on the provider and
 * satisfied by a thin HTTP client on the consumer — no caller changes. Its parameter
 * and result are the context's own published DTOs ({@link StockQuery} /
 * {@link StockAvailabilityReport}), never inventory's internal domain types.
 *
 * <p>Deliberately scoped to <em>offerability</em> (is the SKU carried and in stock at
 * all), not exact-quantity sufficiency: an authoritative quantity reservation is a
 * state change and stays on the asynchronous reserve-stock path, where it can be made
 * atomic and compensated. A synchronous quantity check could only ever be advisory —
 * the level may change between this call and the reservation.
 */
public interface StockAvailabilityApi {

    /**
     * @param query the SKUs whose current offerability the caller wants to know
     * @return per requested SKU, whether inventory can currently offer it
     */
    StockAvailabilityReport check(StockQuery query);
}

/**
 * How-to for reliable integration events: a business use case that publishes
 * through the transactional outbox ({@link com.example.howto.ReservationService}),
 * the integration event it emits ({@link com.example.howto.ReservationPlaced}),
 * and an idempotent consumer guarded by the inbox
 * ({@link com.example.howto.ReservationProjection}).
 */
package com.example.howto;

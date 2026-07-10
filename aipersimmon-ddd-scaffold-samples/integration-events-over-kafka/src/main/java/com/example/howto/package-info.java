/**
 * How-to for integration events over Kafka: {@link com.example.howto.ReservationService}
 * publishes {@link com.example.howto.ReservationPlaced} through the transactional
 * outbox; the outbox relay ships it to a Kafka topic; the messaging starter's
 * consumer bridge reads it, deduplicates on the event id via the inbox, and
 * republishes it in process, where {@link com.example.howto.ReservationView} applies
 * it to a read view.
 */
package com.example.howto;

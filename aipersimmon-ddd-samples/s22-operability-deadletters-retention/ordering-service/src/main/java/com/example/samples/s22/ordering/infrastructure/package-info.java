/**
 * Persistence for the order aggregate. Nothing here knows about the outbox: the outbox row is written
 * by the {@code IntegrationEvents} implementation inside the same transaction, which is why neither
 * side has to coordinate with the other — and why a retention job that deletes outbox rows cannot
 * disturb business state.
 */
package com.example.samples.s22.ordering.infrastructure;

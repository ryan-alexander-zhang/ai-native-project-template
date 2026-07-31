package com.aipersimmon.ddd.integration;

/**
 * A consumer-side translation of one retired revision of a published event into its successor, so
 * handlers only ever face the newest revision they know (issue-00142).
 *
 * <p>The {@link IntegrationEventCatalog} identifies an inbound message by its exact {@code (name,
 * version)} and deliberately never falls back — an unregistered version is dead-lettered, not
 * guessed at. What the catalog alone cannot do is <em>normalise</em>: without upcasters, version
 * coexistence means one listener method per historical revision, in every consumer, forever —
 * published contracts live for years and their version count only grows. An upcaster moves that
 * cost to the contract boundary, registered once per retired revision instead of once per consumer
 * method.
 *
 * <p>Both type parameters must carry {@link EventType} with the <strong>same logical name</strong>
 * and a strictly increasing version — the registration is read entirely from the two contracts, so
 * it cannot drift from them, and the consuming bridge refuses at startup anything it cannot verify.
 * Upcasters chain: a v1 → v2 upcaster and a v2 → v3 upcaster together carry a v1 message to v3,
 * each revision bump adding exactly one hop. An inbound version with no upcaster still arrives as
 * its own class (a listener for the old revision keeps working during a migration), and an
 * <em>unregistered</em> version is still dead-lettered — normalisation loosens nothing about the
 * strict inbound boundary.
 *
 * <p>Implementations are plain total functions of the payload: no I/O, no lookup, no failure path
 * for well-formed input. What the old revision never carried, the upcast must not invent — the
 * successor contract has to tolerate the absence (a nullable addition, a documented default),
 * because a fabricated value is indistinguishable from a real one at the point of use.
 *
 * @param <F> the retired revision this upcaster reads
 * @param <T> the successor revision it produces
 */
public interface EventUpcaster<F extends IntegrationEvent, T extends IntegrationEvent> {

  /** Translates one message of the retired revision into its successor. */
  T upcast(F event);
}

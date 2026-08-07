package com.example.samples.s05.catalog.application;

import com.aipersimmon.ddd.cqrs.ReadModel;

/**
 * The mirror, as it reports itself.
 *
 * <p>{@code upstreamRevision} is deliberately part of the answer: "which version of upstream truth
 * am I looking at" is the first question anyone debugging a stale mirror asks, and a mirror that
 * cannot answer it forces the answer to be guessed from timestamps.
 */
@ReadModel
public record ProductView(String sku, String name, long priceCents, long upstreamRevision) {}

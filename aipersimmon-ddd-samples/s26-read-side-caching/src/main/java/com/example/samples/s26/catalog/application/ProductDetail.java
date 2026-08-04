package com.example.samples.s26.catalog.application;

/**
 * One product as a page shows it — and one cache entry with two different consistency guarantees
 * inside it, which is the most useful uncomfortable fact in this scenario.
 *
 * <p>{@code name} and {@code priceCents} are the catalogue's own facts. When they change, this entry is
 * evicted, so a reader sees the new value on the next request: <strong>correct within one commit</strong>.
 *
 * <p>{@code soldRecently} is derived from {@code s26_order_line}, which is appended to by every sale.
 * Nothing evicts this entry when a sale happens, on purpose — a value that is invalidated on every write
 * has no cache, only overhead, and sales are the highest-volume write in the system. So this number is
 * <strong>correct within one TTL</strong>, and deliberately so.
 *
 * <p>Which means the entry as a whole is only as good as its weakest component. That is the trade to be
 * explicit about rather than to discover: a single cached value cannot offer two guarantees, so either
 * the weakest one is acceptable for every field in it, or the value has to be split into two entries with
 * two TTLs and two eviction rules — twice the keys, twice the round trips, and a page that has to
 * reassemble them. This sample chose one entry and a bounded staleness, because the sales figure is
 * decoration and the price is not, and evicting on price change protects the part that matters.
 * {@code BoundedStalenessTest} asserts both halves of that choice.
 */
public record ProductDetail(String sku, String name, long priceCents, long soldRecently) {}

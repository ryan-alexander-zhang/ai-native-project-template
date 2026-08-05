package com.example.samples.s25.legacy;

/**
 * An order as the monolith describes it: a long id and a status that is a string.
 *
 * <p>A legacy type, and it must not leave this package. {@code status} is free text with four values that are
 * almost an enum; {@code totalCents} is a number with no currency because the monolith has always been in one.
 * Both are facts about the old system, and both are exactly what an anti-corruption layer exists to stop
 * spreading — see {@code acl.LegacyOrders}, and the ArchUnit rule that pins it.
 */
public record LegacyOrderRecord(long id, String customerRef, String status, long totalCents) {}

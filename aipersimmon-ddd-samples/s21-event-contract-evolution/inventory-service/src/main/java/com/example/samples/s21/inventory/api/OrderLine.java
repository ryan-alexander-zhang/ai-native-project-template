package com.example.samples.s21.inventory.api;

/**
 * A line, as v2 introduced it and v3 kept it.
 *
 * <p>Shared by both revisions deliberately: it is the same wire shape, so duplicating it would invite
 * the two copies to drift. A nested type that <em>does</em> change between revisions must be
 * duplicated instead — the retired revision has to keep deserializing the bytes it was written with,
 * and it cannot do that through a class that has moved on.
 */
public record OrderLine(String sku, int quantity) {}

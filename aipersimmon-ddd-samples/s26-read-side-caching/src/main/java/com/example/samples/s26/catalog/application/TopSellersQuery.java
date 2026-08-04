package com.example.samples.s26.catalog.application;

import com.aipersimmon.ddd.cqrs.Query;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import java.util.List;

/**
 * The best-sellers list, answered from the projection.
 *
 * <p>It pointedly does not wear {@link CachedQuery}. It could be cached — one key, one list, a short TTL
 * — but the reason to notice is the other direction: <strong>it could not have been answered by a cache
 * in the first place.</strong> Somebody has to sort the catalogue before there is a list to store, and
 * the only things that can sort it are the database and the projection. A cache in front of this query
 * would be a cache in front of a projection, which is a fine third layer and a different decision.
 */
public record TopSellersQuery(@Positive @Max(100) int limit) implements Query<List<TopSeller>> {}

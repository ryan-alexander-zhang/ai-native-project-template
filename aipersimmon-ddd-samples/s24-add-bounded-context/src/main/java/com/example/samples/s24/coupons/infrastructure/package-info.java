/**
 * The coupons context's persistence, and it touches only tables prefixed {@code s24_coupons_}.
 *
 * <p>Which is the single most load-bearing fact about whether this context could still be lifted out. A join to
 * {@code s24_ordering_order} would work today, would be faster than asking, and would be the thing that has to be
 * unpicked first on the day the two contexts are separated — by which time there will be several.
 * {@code TableOwnershipTest} reads the SQL and checks it.
 */
package com.example.samples.s24.coupons.infrastructure;

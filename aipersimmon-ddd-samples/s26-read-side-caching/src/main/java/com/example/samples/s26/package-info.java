/**
 * S26: "the query is too slow" has two answers, and they are not interchangeable.
 *
 * <p>One is a projection — S12's subject, and present here in miniature as {@code s26_product_sales}.
 * The other is a cache, which the library supplies nothing for: there is no cache module, no port a
 * cache implements, and no configuration property that turns one on. What the library does supply is
 * the one seam a cache belongs on ({@code QueryInterceptor}) and the one place it must never be put
 * (an aggregate), and both of those are demonstrated rather than asserted.
 *
 * <p>The comparison is deliberately made over <em>one number</em>: how much of a product has sold
 * recently. It is computed from {@code s26_order_line} on every read (expensive), cached under the
 * product's detail key (cheap and stale), and maintained at write time in a projection table
 * (cheap, sortable, rebuildable). Same value, three costs, three failure modes.
 */
package com.example.samples.s26;

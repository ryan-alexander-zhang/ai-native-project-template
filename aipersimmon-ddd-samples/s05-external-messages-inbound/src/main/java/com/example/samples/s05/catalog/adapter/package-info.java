/**
 * The inbound edge, including the anti-corruption layer: the one place in this service that knows
 * what an ERP message looks like.
 *
 * <p>Everything foreign stops here — field names, units, the currency, the kind discriminator, the
 * failure classification. What leaves is a command in this context's language, indistinguishable
 * from one an HTTP request would have produced.
 *
 * <p>The HTTP read lives here too, and its neighbour is the point: the request that arrives already
 * translated and the message that has to be translated end up in the same shape before either one
 * reaches the application layer.
 */
package com.example.samples.s05.catalog.adapter;

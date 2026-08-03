/**
 * The anti-corruption layer: the one place in this service that knows what an ERP message looks like.
 *
 * <p>Everything foreign stops here — field names, units, the currency, the kind discriminator, the
 * failure classification. What leaves is a command in this context's language, indistinguishable from
 * one an HTTP request would have produced.
 */
package com.example.samples.s05.catalog.adapter;

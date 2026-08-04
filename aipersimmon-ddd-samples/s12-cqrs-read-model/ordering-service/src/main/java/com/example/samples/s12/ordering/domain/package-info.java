/**
 * The order aggregate — the write model, and the only truth in this service.
 *
 * <p>Read it for what is absent: no projection, no read model, no denormalised summary, no staleness flag.
 * The aggregate records that things happened. Everything the list page needs is derived from here by
 * something the aggregate has never heard of.
 */
package com.example.samples.s12.ordering.domain;

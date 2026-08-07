/**
 * The HTTP edge, plus the one place this service says how a dependency outage should look to a client.
 *
 * <p>Both live here because both are transport decisions: the domain knows there are two distinct
 * failures, and the adapter layer knows one is a 422 and the other a 503.
 */
package com.example.samples.s06.ordering.adapter;

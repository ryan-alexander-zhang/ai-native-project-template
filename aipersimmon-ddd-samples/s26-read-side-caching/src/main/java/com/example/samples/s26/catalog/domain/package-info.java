/**
 * The product aggregate and the facts it publishes when it changes.
 *
 * <p>Nothing here knows that a cache exists, and that is load-bearing rather than tidy: the events
 * below are what an invalidation listens to, so the domain announces <em>what changed</em> and the
 * read side decides what that costs it. A domain that evicted cache keys would have to be told about
 * every new read model ever added.
 */
package com.example.samples.s26.catalog.domain;

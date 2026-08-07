/**
 * Our own edge: the API our clients call, and the timer that starts a reconciliation round.
 *
 * <p>What is <em>not</em> here is the provider's callback endpoint, which lives in
 * {@code infrastructure.gateway} — the one place this sample departs from the layout of the others, argued
 * in that package's own documentation. The short version: this package holds surfaces we designed, and a
 * webhook is a surface somebody else designed.
 */
package com.example.samples.s07.payments.adapter;

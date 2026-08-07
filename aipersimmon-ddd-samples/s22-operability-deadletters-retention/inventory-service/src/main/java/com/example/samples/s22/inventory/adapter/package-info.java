/**
 * The inbound adapter: the integration-event subscriber, and the read endpoint that makes a
 * reservation observable from outside the process.
 *
 * <p>Subscribers live here because an integration event arrives over a transport at the boundary — a
 * library ArchUnit rule says so, and the rule is enforcing the reason. The endpoint an operator hits
 * after replaying a quarantined message is the same kind of thing: a transport, translated inward.
 */
package com.example.samples.s22.inventory.adapter;

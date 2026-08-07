/**
 * The inbound edge, both kinds of it: the subscriber a transport calls, and the HTTP read that makes
 * the effect of a consumed event observable from outside.
 *
 * <p>They used to be two packages, and the stated reason was that the library's rule for
 * integration-event subscribers named {@code ..adapter..} while the controller sat in {@code
 * ..interfaces..}. That was the tooling choosing the layout: the rule now recognises both spellings,
 * so the split has no reason left. The two edges do differ — one is called by a client, the other by
 * a transport that will call again — but that is a difference between two classes, not between two
 * layers, and both drive a use case through the bus without deciding anything themselves.
 */
package com.example.samples.s04.inventory.adapter;

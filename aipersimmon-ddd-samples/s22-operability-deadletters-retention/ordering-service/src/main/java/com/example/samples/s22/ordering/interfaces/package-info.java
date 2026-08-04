/**
 * HTTP: the business entry, and the operations entry.
 *
 * <p>They are two controllers rather than one because they have different audiences, and in a real
 * deployment different exposure — {@code /ops/**} belongs behind whatever protects the admin plane,
 * on a management port or an internal ingress. This sample has no security module, so the split is
 * the only part of that it can honestly show; the alternative (one controller carrying both) makes
 * the split impossible to add later without moving code.
 */
package com.example.samples.s22.ordering.interfaces;

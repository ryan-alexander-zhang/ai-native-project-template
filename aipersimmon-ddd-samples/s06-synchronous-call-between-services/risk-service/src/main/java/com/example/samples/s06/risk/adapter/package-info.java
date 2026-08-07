/**
 * The HTTP edge, which for a callee <em>is</em> the published contract.
 *
 * <p>There is no {@code ..api..} package here because nothing is published as an event. What the caller
 * depends on is the request shape, the response shape and the status codes — so those three are the
 * things that may not change casually, and the ones the companion document argues about.
 */
package com.example.samples.s06.risk.adapter;

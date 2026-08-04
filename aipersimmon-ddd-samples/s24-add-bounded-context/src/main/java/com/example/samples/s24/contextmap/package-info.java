/**
 * The context map: where the relationships between contexts live.
 *
 * <p>It depends on two published contracts and is depended on by neither, which is what keeps the contexts themselves
 * acyclic. Without it, the two integrations this service has — a synchronous quote and an asynchronous redemption —
 * would point in opposite directions and make ordering and coupons mutually dependent.
 *
 * <p>It is legal under the library's isolation rule for the ordinary reason: it only ever touches {@code ..api..}
 * packages. It is not a bounded context and holds no model; if a rule ever appears in here, it belongs to one of the
 * contexts and the question is which.
 *
 * <p>This is also the package that disappears when the contexts are split: its contents become a broker subscription in
 * one service and an HTTP client in the other.
 */
package com.example.samples.s24.contextmap;

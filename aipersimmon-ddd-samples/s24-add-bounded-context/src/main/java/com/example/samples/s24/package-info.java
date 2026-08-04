/**
 * S24: adding a bounded context to a service that already has two.
 *
 * <p>Three contexts and a shared kernel in one module, because the scenario's first answer is that <strong>a new context
 * starts as a package, not as a Maven module</strong>. What makes the module option available later is a set of
 * disciplines that cost nothing on day one and are all measured here:
 *
 * <ul>
 *   <li><strong>api only.</strong> A context is entered through its {@code api} package and nowhere else — the library's
 *       {@code BoundedContextRules}, plus a rule the library does not have: {@code api} depends on nothing inside its own
 *       context, so a published contract cannot leak a model;
 *   <li><strong>no cycles.</strong> The library's rule permits two contexts to depend on each other's contracts. Maven
 *       does not permit two modules to. So the cycle is forbidden now, and the subscription that would have created one
 *       lives in {@code contextmap};
 *   <li><strong>domain knows nothing.</strong> No {@code domain} package depends on another context at all, published
 *       contract included. Cross-context collaboration is an application-layer job, because that is where a transaction
 *       and a retry can be reasoned about;
 *   <li><strong>one prefix per context.</strong> Every table is {@code s24_<context>_...}, which makes "has anybody
 *       queried across the boundary" mechanically answerable — {@code TableOwnershipTest} reads the SQL and checks;
 *   <li><strong>its own migration.</strong> A context whose columns were added to existing migration files has nothing to
 *       move on the day it leaves.
 * </ul>
 *
 * <p>And the first integration, which is the question everybody actually asks: <strong>both</strong>, split by whether the
 * answer is needed to decide or only to record. Pricing needs the discount before it can proceed, so that is a call.
 * Counting the redemption is a consequence, so that is an event after commit. What that costs — a window in which the two
 * contexts disagree — is measured in {@code QuoteAndRedeemTest} rather than argued about.
 */
package com.example.samples.s24;

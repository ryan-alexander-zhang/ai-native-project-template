/**
 * The shared kernel, and it is under {@code api} for a reason the library's own rule hands you.
 *
 * <p>{@code BoundedContextRules.dependOnEachOtherOnlyThroughApi} treats every immediate sub-package of the base
 * package as a context, and {@code sharedkernel} is one of them. So the only way another context may use anything
 * from here is through {@code sharedkernel.api} — put {@code Money} one package up and the rule reports a violation
 * for every context that touches it. {@code ArchitectureTest} measures that; the analysis document's negative
 * controls measure how many.
 *
 * <p>Which turns out to be the right shape rather than a technicality worked around. A shared kernel is the most
 * published contract in a codebase: several teams depend on it, none of them owns it, and a change to it is a
 * negotiation. Giving it a published-contract package says so, and it leaves room for the thing a shared kernel
 * must never have — an internal part. There is no {@code sharedkernel.domain} and there will not be one: a shared
 * kernel with private internals is a shared model, and a shared model is what bounded contexts exist to avoid.
 */
package com.example.samples.s24.sharedkernel.api;

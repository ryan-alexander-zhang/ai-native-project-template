/**
 * The coupons use cases, plus the two classes that implement the published boundary.
 *
 * <p>{@code QuoteCoupons} and {@code RecordRedemptions} live here rather than in {@code api} on purpose: the interfaces
 * up there are the contract, these are one way of satisfying it. After the context becomes its own service, ordering
 * gets a different implementation of the same interfaces and these two stay where they are.
 *
 * <p>This is also the layer where cross-context collaboration is allowed to happen at all — the domain is forbidden
 * from knowing another context exists, and the reason is that a transaction, a failure and a retry can only be
 * reasoned about here.
 */
package com.example.samples.s24.coupons.application;

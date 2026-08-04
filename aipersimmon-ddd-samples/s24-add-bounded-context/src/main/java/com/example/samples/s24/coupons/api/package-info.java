/**
 * What the coupons context publishes — five types, and the list is the answer to the catalogue's second question.
 *
 * <h2>What must be here</h2>
 *
 * <ul>
 *   <li>{@code CouponCode} — the identifier, because another context has to be able to hold a reference;
 *   <li>{@code CouponQuotes} + {@code CouponQuote} — the synchronous question and its answer, because pricing an
 *       order needs the answer before it can proceed;
 *   <li>{@code CouponRedemptions} — the verb the outside world may invoke, as one method rather than as a published
 *       command record;
 *   <li>{@code CouponRedeemed} — the fact others may react to.
 * </ul>
 *
 * <h2>What must not</h2>
 *
 * <ul>
 *   <li>the aggregate. {@code Coupon} is in {@code domain} and stays there. Publishing it would publish its
 *       invariants, its setters-by-another-name, and the fact that it has a version;
 *   <li>the repository port. {@code Coupons} is how this context loads its own aggregate; a caller that could load
 *       one would be sharing the model, not using a contract;
 *   <li>the commands. {@code IssueCoupon} is this context's use case, and its shape — field names, validation
 *       annotations, return type — is not a promise;
 *   <li>the enum behind the discount. {@code CouponKind} decides arithmetic that {@code CouponQuote} has already
 *       performed. Exporting it would invite a caller to do the sum itself;
 *   <li>anything from {@code infrastructure}. Obvious, and worth a rule anyway.
 * </ul>
 *
 * <h2>The mechanical property</h2>
 *
 * <p>All of that reduces to one checkable statement: <strong>{@code api} depends on nothing else in its own
 * context.</strong> It is a leaf. If a published type needed the domain, the domain would be published. The library
 * has no rule for this — {@code BoundedContextRules} checks that <em>others</em> come in through {@code api}, not
 * that {@code api} is clean — so {@code ArchitectureTest.theapiPackagesAreLeaves} adds it. That one rule is worth
 * more than the prose above, because it is the one that will still be true in two years.
 *
 * <p>It may depend on the shared kernel, and does: {@code CouponQuote} carries {@code Money}. That is what a shared
 * kernel is for — a language two contracts can both be written in without either owning it.
 */
package com.example.samples.s24.coupons.api;

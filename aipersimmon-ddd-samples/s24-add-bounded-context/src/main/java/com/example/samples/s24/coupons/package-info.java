/**
 * The coupons context — the one this scenario adds — and the shape every new context starts in.
 *
 * <p>Five packages, and the order they are worth creating in:
 *
 * <ol>
 *   <li>{@code api} first, even before there is anything to put in it. It is the only package another context may
 *       touch, and creating it last means writing it after the coupling has already happened;
 *   <li>{@code domain} — the model, which is forbidden from knowing another context exists;
 *   <li>{@code application} — the use cases, and the only layer where cross-context collaboration is allowed;
 *   <li>{@code infrastructure} — persistence, over tables prefixed {@code s24_coupons_} and no others;
 *   <li>{@code adapter} — its own edge, if it has one.
 * </ol>
 *
 * <p>Plus four things that belong to day one rather than to a later tidy-up, each of which is measured:
 * the table prefix, the {@code BoundedContextRules} isolation rule, a {@code package-info} per package, and its own
 * migration file. A context whose columns were scattered through the existing migrations has nothing to move on the day
 * it leaves.
 */
package com.example.samples.s24.coupons;

/**
 * The refunds use cases, and the one place the order's facts are fetched.
 *
 * <p>{@code OrderFacts} is declared here rather than in the ACL package on purpose: the new context states what it
 * needs and the ACL satisfies it. Declared the other way round, the legacy vocabulary decides the interface.
 */
package com.example.samples.s25.refunds.application;

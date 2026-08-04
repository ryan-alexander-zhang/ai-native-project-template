/**
 * The payment write model — and the part of this sample that has never heard of a payment gateway.
 *
 * <p>No HTTP, no signatures, no result codes, no provider names. What the outside world's unreliability
 * leaves behind here is only shape: states that move in one direction, an answer that may arrive twice,
 * and a place to record that we do not know. That is the test of an anticorruption layer — not that a
 * translation class exists, but that the model on this side is expressible without the provider.
 */
package com.example.samples.s07.payments.domain;

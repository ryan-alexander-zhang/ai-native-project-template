/**
 * The points aggregate, carrying both participation shapes and knowing about neither Seata nor HTTP.
 *
 * <p>Read for what is absent: no XID, no branch id, no {@code BusinessActionContext}, no annotation from
 * a transaction framework. {@code frozen} is here because "promised points" is a fact about points — the
 * fact TCC needs, but not a fact TCC invented. If it had to be introduced solely to satisfy the protocol,
 * that would be the signal to use AT instead.
 */
package com.example.samples.s10.points.domain;

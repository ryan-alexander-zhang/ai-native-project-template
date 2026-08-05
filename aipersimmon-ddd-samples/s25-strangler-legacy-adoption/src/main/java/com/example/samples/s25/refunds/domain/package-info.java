/**
 * The refunds model, over a table the library did not design.
 *
 * <p>Three things it does not have, each because the transition forbids it: no {@code Money} (the monolith has always
 * been in one currency, and introducing one here would be a second migration), no renamed states (the enum is the
 * legacy column's three strings), and no reference to the order beyond its {@code bigint} — the order's facts arrive
 * as arguments, because the order still lives in the monolith and a model that could read it would have invariants
 * depending on legacy SQL.
 */
package com.example.samples.s25.refunds.domain;

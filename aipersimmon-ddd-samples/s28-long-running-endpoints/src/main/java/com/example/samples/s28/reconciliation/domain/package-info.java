/**
 * The reconciliation model: two jobs, and a careful line around what belongs to them.
 *
 * <p>Both aggregates here exist because their <em>lifecycle</em> carries rules — an export cannot succeed
 * twice or be finished by a superseded worker; a batch cannot complete with chunks missing. Neither owns the
 * high-volume data that flows through it: not the rows being exported, not the progress of the export, not
 * the chunk receipts of the upload. Those live in tables of their own and reach the aggregate as arguments,
 * because an aggregate is a consistency boundary and a consistency boundary that grows with the payload is
 * the wrong boundary.
 *
 * <p>Nothing in this package knows what a file is, where artifacts live, or how a row is read from the
 * database — {@code Artifact} carries an opaque path and stops there. An {@code ArchitectureTest} rule pins
 * that, because "the domain needs a {@code Path} for a moment" is how a streaming concern ends up inside an
 * invariant.
 */
package com.example.samples.s28.reconciliation.domain;

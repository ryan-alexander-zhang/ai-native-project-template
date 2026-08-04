/**
 * The use cases, and the shape of an endpoint that cannot answer inside a request.
 *
 * <p>Three arrangements in here carry the scenario:
 *
 * <ul>
 *   <li>{@link com.example.samples.s28.reconciliation.application.ExportClaims} — the one write that is
 *       deliberately not a version-checked aggregate save, with the argument for why and the cost of it;
 *   <li>{@link com.example.samples.s28.reconciliation.application.ExportRunner} — what runs in which
 *       transaction, and why the answer is "as little as possible, and not the export";
 *   <li>{@link com.example.samples.s28.reconciliation.application.ProgressBoard} — the data that is
 *       deliberately not part of the aggregate, and the connection it needs to be useful.
 * </ul>
 *
 * <p>{@link com.example.samples.s28.reconciliation.application.InlineExport} is the synchronous version, kept
 * because the first question is where its limit is and the answer is measured against it rather than asserted.
 */
package com.example.samples.s28.reconciliation.application;

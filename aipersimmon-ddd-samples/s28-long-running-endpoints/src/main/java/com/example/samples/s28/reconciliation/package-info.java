/**
 * Bulk data exchange: exports out, imports in, and neither of them finishes inside a request.
 *
 * <p>One context, because both directions answer the same questions with the same vocabulary — a job, a claim, a
 * progress reading, an artifact, an idempotent submission — and splitting them would have meant saying all of it
 * twice.
 */
package com.example.samples.s28.reconciliation;

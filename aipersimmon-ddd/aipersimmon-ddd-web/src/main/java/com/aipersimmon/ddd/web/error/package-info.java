/**
 * The error contract: an RFC 9457-aligned, framework-free error model.
 *
 * <p>A domain {@link com.aipersimmon.ddd.core.error.ErrorCode} names <em>what</em> failed;
 * a {@link com.aipersimmon.ddd.web.error.ProblemDescriptor} names <em>how it renders</em>
 * (type/status/title) — the two are separate, joined only at the edge. Every code resolves
 * to a descriptor through a {@link com.aipersimmon.ddd.web.error.ProblemRegistry}: its
 * category's {@link com.aipersimmon.ddd.web.error.DefaultProblemFamilies family default},
 * or a {@link com.aipersimmon.ddd.web.error.ProblemCatalog} override for the few codes that
 * warrant their own problem type. {@link com.aipersimmon.ddd.web.error.ApiError} is the
 * value model a starter renders to {@code application/problem+json}: the five standard
 * members plus the extension members {@code code}, {@code requestId}, {@code traceId} and a
 * {@link com.aipersimmon.ddd.web.error.FieldError} list.
 * {@link com.aipersimmon.ddd.web.error.ApiException} lets application code raise a
 * catalogued error directly, carrying an {@code ErrorCode}.
 */
package com.aipersimmon.ddd.web.error;

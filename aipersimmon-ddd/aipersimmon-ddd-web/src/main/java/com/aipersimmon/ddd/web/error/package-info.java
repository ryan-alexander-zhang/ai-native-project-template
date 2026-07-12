/**
 * The error contract: an RFC 9457-aligned, framework-free error model.
 *
 * <p>{@link com.aipersimmon.ddd.web.error.ProblemType} is the abstraction for an
 * error catalogue — each bounded context implements it, typically as an enum, so
 * every error has a stable machine code, a type identifier, a default HTTP status,
 * and an i18n title key. {@link com.aipersimmon.ddd.web.error.ApiError} is the
 * value model a starter renders to {@code application/problem+json}: the five
 * standard members plus the extension members {@code code}, {@code traceId} and a
 * {@link com.aipersimmon.ddd.web.error.FieldError} list. {@link com.aipersimmon.ddd.web.error.ApiException}
 * lets application code raise a catalogued error directly.
 */
package com.aipersimmon.ddd.web.error;

/**
 * Framework-free contracts for the inbound HTTP (interface) layer.
 *
 * <p>The library takes the "no envelope" stance: a successful response returns the resource
 * directly with the correct HTTP status code, and a failure is expressed as an RFC 9457 problem
 * document. This package holds the vocabulary a REST adapter needs to do that without depending on
 * any web framework:
 *
 * <ul>
 *   <li>{@link com.aipersimmon.ddd.web.error} — a {@code ProblemDescriptor} + {@code
 *       ProblemRegistry} mapping (code → problem type) and an {@code ApiError} value model shaped
 *       after RFC 9457, kept independent of Spring's {@code ProblemDetail}.
 *   <li>{@link com.aipersimmon.ddd.web.page} — cursor-first {@code Slice}/{@code Page} value
 *       objects and an opaque {@code Cursor}.
 *   <li>{@link com.aipersimmon.ddd.web.spi} — the cross-cutting SPIs whose state a backend must
 *       hold: idempotency, replay protection, rate limiting, and request-signature verification.
 * </ul>
 *
 * <p>A Spring starter implements these ports; pluggable Redis/JDBC backends implement the SPIs.
 * Nothing here depends on Spring, Jackson, or the servlet API.
 */
package com.aipersimmon.ddd.web;

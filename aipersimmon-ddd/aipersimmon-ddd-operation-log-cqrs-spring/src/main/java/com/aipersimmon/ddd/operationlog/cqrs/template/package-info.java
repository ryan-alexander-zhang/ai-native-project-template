/**
 * The restricted, compile-at-startup template engine (not SpEL): {@link
 * com.aipersimmon.ddd.operationlog.cqrs.template.RestrictedTemplate} evaluates null-safe read-only
 * property paths over a fixed set of allowlisted root objects plus a tiny pure-function whitelist
 * ({@code mask}, {@code truncate}, {@code defaultValue}). No bean lookup, no {@code T(...)}, no
 * constructors, no arbitrary method calls, no reflection beyond a named no-arg accessor.
 */
package com.aipersimmon.ddd.operationlog.cqrs.template;

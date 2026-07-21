/**
 * A framework-free error-code vocabulary — {@link com.aipersimmon.ddd.core.error.ErrorCode} and its
 * coarse {@link com.aipersimmon.ddd.core.error.ErrorCategory} — that a domain exception can carry
 * so a stable, machine-readable code travels from where the error is raised out to the system's
 * edge, without the domain knowing anything about transport or HTTP status.
 */
package com.aipersimmon.ddd.core.error;

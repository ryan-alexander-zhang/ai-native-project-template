/**
 * Spring Web implementation of the web contracts.
 *
 * <p>Auto-configured when this starter is on the classpath: an exception advice that renders {@link
 * com.aipersimmon.ddd.web.error.ApiError} to Spring's {@code ProblemDetail} (RFC 9457), a trace-id
 * filter, cursor-aware Jackson (de)serialization for {@link
 * com.aipersimmon.ddd.web.page.Slice}/{@link com.aipersimmon.ddd.web.page.Page}, and i18n title
 * resolution for {@link com.aipersimmon.ddd.web.error.ProblemDescriptor}s. Each concern is toggled
 * by {@code aipersimmon.ddd.web.*} and can be replaced by a consumer bean.
 */
package com.aipersimmon.ddd.web.spring;

/**
 * Spring Boot auto-configuration that turns on springdoc and documents the framework's
 * cross-cutting web contracts in the generated OpenAPI spec.
 *
 * <p>springdoc documents every {@code @RestController} by reflection — no annotations required, so
 * read models ({@code @ReadModel}) reach the spec straight from the application tier without any
 * OpenAPI dependency on them. What reflection cannot see is the shared error contract: the {@code
 * application/problem+json} (RFC 9457) responses the framework's {@code @RestControllerAdvice}
 * produces. {@link com.aipersimmon.ddd.openapi.autoconfigure.ProblemResponsesCustomizer} fills that
 * gap by attaching a default family of problem responses to every operation, which an operation may
 * override by declaring its own response for the same status code.
 */
package com.aipersimmon.ddd.openapi.autoconfigure;

package com.aipersimmon.ddd.archunit.fixture.apidoc.ordering.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A fixture domain type that (wrongly) carries an OpenAPI schema annotation. It exists only so
 * {@link com.aipersimmon.ddd.archunit.LayeringRules#domainShouldNotDependOnApiDocumentation()} has
 * a real violation to catch — the domain must stay free of transport/documentation frameworks.
 * (Application read models and adapter DTOs are allowed to carry {@code @Schema}, so a domain type
 * is the only place this rule fires.)
 */
@Schema(description = "domain type that should not carry OpenAPI annotations")
public record BadDomainTypeWithSchema(String id) {}

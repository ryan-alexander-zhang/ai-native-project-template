package com.aipersimmon.ddd.archunit.fixture.bad.ordering.adapter;

import com.aipersimmon.ddd.application.UseCase;

/**
 * Violates use-case placement: a {@link UseCase @UseCase} type in the interface/adapter layer. A
 * use case orchestrates the domain through its ports and belongs to the application layer, not an
 * adapter. Trips {@code useCasesShouldResideInApplication}.
 */
@UseCase
public class BadUseCaseInAdapter {}

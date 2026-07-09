/**
 * Domain layer of the ordering context: the model and business rules, free of
 * framework and infrastructure concerns. Organised one package per aggregate
 * ({@code order}, {@code customer}) plus a {@code shared} package for value
 * objects used across this context's aggregates.
 */
@DomainLayer
package com.example.ordering.domain;

import com.aipersimmon.ddd.core.architecture.DomainLayer;

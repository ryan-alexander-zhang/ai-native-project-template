package com.example.samples.s23.ordering.application;

import com.aipersimmon.ddd.cqrs.ReadModel;

/**
 * An order, as this service reports it during and after the migration.
 *
 * <p>{@code handling} is null while the backfill has not reached this row, and the answer says so
 * rather than guessing. A field that reported {@code STANDARD} for an undecided row would be a lie
 * the API tells for as long as the backfill takes — which is the whole reason this sample keeps
 * "not yet decided" and "decided to be standard" as different answers.
 *
 * <p>{@code street} and {@code city} are flattened out of the {@code ShipTo} value object on
 * purpose: the wire shape and the model's shape are allowed to differ, and a read model is where
 * that difference is written down.
 */
@ReadModel
public record OrderView(
    String id,
    String customerId,
    String sku,
    int quantity,
    String street,
    String city,
    String handling) {}

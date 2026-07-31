package com.aipersimmon.ddd.archunit.fixture.validation.good;

/**
 * Not a command — a record like this (read model, projection, DTO) never enters through the command
 * bus, so the validation-contract rule must leave its bare components alone.
 */
public record GoodPlainReadModel(String orderId, String status) {}

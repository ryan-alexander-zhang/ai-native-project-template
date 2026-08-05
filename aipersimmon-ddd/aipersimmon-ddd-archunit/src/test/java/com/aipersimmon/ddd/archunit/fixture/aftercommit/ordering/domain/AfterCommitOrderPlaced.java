package com.aipersimmon.ddd.archunit.fixture.aftercommit.ordering.domain;

import com.aipersimmon.ddd.core.event.DomainEvent;

/** The domain event the isolated after-commit fixture subscribes to. */
public class AfterCommitOrderPlaced implements DomainEvent {}

package com.aipersimmon.ddd.archunit.fixture.bad.ordering.domain;

import com.aipersimmon.ddd.archunit.fixture.bad.ordering.application.BadService;

/** Violates the layering: a domain class depending on the application layer. */
public class BadOrder {

  private final BadService service;

  public BadOrder(BadService service) {
    this.service = service;
  }

  public BadService service() {
    return service;
  }
}

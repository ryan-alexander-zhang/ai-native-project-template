package com.aipersimmon.ddd.archunit.fixture.good.ordering.domain;

/** Well-behaved domain class: depends on nothing in an outer layer. */
public class GoodOrder {

  private final String id;

  public GoodOrder(String id) {
    this.id = id;
  }

  public String id() {
    return id;
  }
}

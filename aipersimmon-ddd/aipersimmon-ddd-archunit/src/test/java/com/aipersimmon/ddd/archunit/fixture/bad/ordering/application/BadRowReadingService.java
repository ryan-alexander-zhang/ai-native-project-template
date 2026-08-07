package com.aipersimmon.ddd.archunit.fixture.bad.ordering.application;

import com.aipersimmon.ddd.archunit.fixture.good.ordering.infrastructure.GoodOrderRowMapper;

/**
 * Violates {@code nothingOutsideInfrastructureShouldDependOnMappersOrRows}: an application class
 * holding the mapper, so the repository port — the one abstraction that survives replacing the
 * persistence technology — has been bypassed and the next migration reaches into this layer.
 */
public class BadRowReadingService {

  private final GoodOrderRowMapper mapper;

  public BadRowReadingService(GoodOrderRowMapper mapper) {
    this.mapper = mapper;
  }

  public long count() {
    return mapper.selectCount(null);
  }
}

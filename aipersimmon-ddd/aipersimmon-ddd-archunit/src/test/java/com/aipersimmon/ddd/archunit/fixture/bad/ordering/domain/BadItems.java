package com.aipersimmon.ddd.archunit.fixture.bad.ordering.domain;

import com.aipersimmon.ddd.core.annotation.Repository;

/**
 * A well-formed repository port used as a helper by the misplaced/mis-annotated implementation
 * fixtures in this package tree. Correct on its own (a {@code @Repository} interface in the
 * domain), so it does not trip {@code repositoryPortsShouldBeInterfacesInDomain}.
 */
@Repository
public interface BadItems {

  void save(String item);
}

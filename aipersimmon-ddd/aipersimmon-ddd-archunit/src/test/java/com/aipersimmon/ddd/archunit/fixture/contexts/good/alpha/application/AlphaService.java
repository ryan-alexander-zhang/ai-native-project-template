package com.aipersimmon.ddd.archunit.fixture.contexts.good.alpha.application;

import com.aipersimmon.ddd.archunit.fixture.contexts.good.beta.api.BetaConfirmed;

/**
 * Alpha depends on the beta context only through beta's {@code ..api..} package, so it respects the
 * published-language boundary. Exercises the good path of {@code
 * boundedContextsShouldOnlyDependOnEachOthersApi}.
 */
public class AlphaService {

  public String describe(BetaConfirmed confirmed) {
    return "alpha reacting to beta " + confirmed.id();
  }
}

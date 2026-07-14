package com.aipersimmon.ddd.archunit.fixture.contexts.good.beta.api;

/** Beta's published contract: what other contexts are allowed to depend on. */
public class BetaConfirmed {

    private final String id;

    public BetaConfirmed(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}

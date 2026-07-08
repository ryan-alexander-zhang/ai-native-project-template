package com.acme.samples.s1;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Verifies the logical bounded-context boundaries: no module reaches into
 * another module's internals, no cycles. This is structure-1's boundary
 * enforcement (test-time), standing in for the compile-time Maven-module
 * boundaries used by structure-2/3.
 */
class ModularityTests {

    @Test
    void verifiesModuleStructure() {
        ApplicationModules.of(S1Application.class).verify();
    }
}

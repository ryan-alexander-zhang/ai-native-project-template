package com.example;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Boots the full application to prove every layer wires together: the web
 * adapter, the use-case services, the in-memory repositories, and the
 * domain-event publisher all resolve.
 */
@SpringBootTest
class ApplicationSmokeTest {

    @Test
    void contextLoads() {
        // The Spring context starting successfully is the assertion.
    }
}

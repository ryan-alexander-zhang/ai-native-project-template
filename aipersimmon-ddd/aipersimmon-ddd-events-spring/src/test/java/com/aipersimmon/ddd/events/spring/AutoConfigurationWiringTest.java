package com.aipersimmon.ddd.events.spring;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.aipersimmon.ddd.application.DomainEvents;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

/** Proves that adding this starter auto-configures a Spring-backed DomainEvents bean. */
@SpringBootTest(classes = AutoConfigurationWiringTest.TestApp.class)
class AutoConfigurationWiringTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {
    }

    @Autowired
    DomainEvents domainEvents;

    @Test
    void autoConfiguresSpringDomainEvents() {
        assertInstanceOf(SpringDomainEvents.class, domainEvents);
    }
}

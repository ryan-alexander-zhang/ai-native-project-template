package com.aipersimmon.ddd.inbox.mybatisplus;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.application.Inbox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Exercises the MyBatis-Plus inbox against an in-memory H2 database. */
@SpringBootTest(classes = InboxMybatisPlusTest.TestApp.class)
class InboxMybatisPlusTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {
    }

    @Autowired
    Inbox inbox;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM aipersimmon_inbox");
    }

    @Test
    void recordsFirstKeyThenDetectsDuplicate() {
        assertFalse(inbox.alreadyProcessed("k1"), "first delivery should be new");
        assertTrue(inbox.alreadyProcessed("k1"), "redelivery of the same key should be detected");
        assertFalse(inbox.alreadyProcessed("k2"), "a different key should be new");
    }

    @Test
    void autoConfiguresMybatisPlusInbox() {
        assertInstanceOf(MybatisPlusInbox.class, inbox);
    }
}

package com.aipersimmon.ddd.web.spring;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * With idempotency enabled, a repeat request under the same key replays the first
 * response without running the handler again, while a different key executes anew.
 */
@SpringBootTest(classes = IdempotencyFilterTest.Config.class,
        properties = "aipersimmon.ddd.web.idempotency.enabled=true")
@AutoConfigureMockMvc
class IdempotencyFilterTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    Counter counter;

    @Test
    void sameKeyReplaysFirstResponseAndRunsHandlerOnce() throws Exception {
        mvc.perform(post("/idem").header("Idempotency-Key", "k1"))
                .andExpect(status().isOk())
                .andExpect(content().string("1"));

        mvc.perform(post("/idem").header("Idempotency-Key", "k1"))
                .andExpect(status().isOk())
                .andExpect(content().string("1"));

        // A different key executes the handler again.
        mvc.perform(post("/idem").header("Idempotency-Key", "k2"))
                .andExpect(status().isOk())
                .andExpect(content().string("2"));
    }

    static class Counter {
        final AtomicInteger value = new AtomicInteger();
    }

    @RestController
    static class IdemController {

        private final Counter counter;

        IdemController(Counter counter) {
            this.counter = counter;
        }

        @PostMapping("/idem")
        String create() {
            return Integer.toString(counter.value.incrementAndGet());
        }
    }

    @Configuration
    @EnableAutoConfiguration
    @Import(IdemController.class)
    static class Config {
        @org.springframework.context.annotation.Bean
        Counter counter() {
            return new Counter();
        }
    }
}

package com.aipersimmon.ddd.web.spring;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * With a rate limit of one request per window, the second request is rejected with 429, a
 * Retry-After, RateLimit headers, and a problem body.
 */
@SpringBootTest(
    classes = RateLimitFilterTest.Config.class,
    properties = {
      "aipersimmon.ddd.web.rate-limit.enabled=true",
      "aipersimmon.ddd.web.rate-limit.limit=1",
      "aipersimmon.ddd.web.rate-limit.window=1m"
    })
@AutoConfigureMockMvc
class RateLimitFilterTest {

  @Autowired MockMvc mvc;

  @Test
  void firstRequestAllowedSecondRejectedWith429() throws Exception {
    mvc.perform(get("/rl"))
        .andExpect(status().isOk())
        .andExpect(header().exists("RateLimit"))
        .andExpect(header().exists("RateLimit-Policy"));

    mvc.perform(get("/rl"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"))
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(
            content().string(org.hamcrest.Matchers.containsString("/problems/rate-limited")));
  }

  @RestController
  static class RlController {
    @GetMapping("/rl")
    String ping() {
      return "ok";
    }
  }

  @Configuration
  @EnableAutoConfiguration
  @Import(RlController.class)
  static class Config {}
}

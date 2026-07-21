package com.aipersimmon.ddd.web.spring;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aipersimmon.ddd.web.spi.RequestSignatureVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * With replay protection enabled (signature + timestamp tolerance + nonce dedup), a well-formed
 * fresh request passes, while a bad signature, a stale timestamp, or a reused nonce are rejected
 * with 401.
 */
@SpringBootTest(
    classes = ReplayProtectionFilterTest.Config.class,
    properties = {
      "aipersimmon.ddd.web.replay.enabled=true",
      "aipersimmon.ddd.web.replay.nonce.enabled=true"
    })
@AutoConfigureMockMvc
class ReplayProtectionFilterTest {

  @Autowired MockMvc mvc;

  private static String now() {
    return Long.toString(System.currentTimeMillis() / 1000);
  }

  @Test
  void validSignedRequestPasses() throws Exception {
    mvc.perform(
            post("/replay")
                .header("X-Signature", "good")
                .header("X-Timestamp", now())
                .header("X-Nonce", "n-valid"))
        .andExpect(status().isOk());
  }

  @Test
  void badSignatureRejected() throws Exception {
    mvc.perform(
            post("/replay")
                .header("X-Signature", "bad")
                .header("X-Timestamp", now())
                .header("X-Nonce", "n-bad"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void staleTimestampRejected() throws Exception {
    String tenMinutesAgo = Long.toString(System.currentTimeMillis() / 1000 - 600);
    mvc.perform(
            post("/replay")
                .header("X-Signature", "good")
                .header("X-Timestamp", tenMinutesAgo)
                .header("X-Nonce", "n-stale"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void reusedNonceRejected() throws Exception {
    mvc.perform(
            post("/replay")
                .header("X-Signature", "good")
                .header("X-Timestamp", now())
                .header("X-Nonce", "n-reuse"))
        .andExpect(status().isOk());

    mvc.perform(
            post("/replay")
                .header("X-Signature", "good")
                .header("X-Timestamp", now())
                .header("X-Nonce", "n-reuse"))
        .andExpect(status().isUnauthorized());
  }

  @RestController
  static class ReplayController {
    @PostMapping("/replay")
    String handle() {
      return "ok";
    }
  }

  @Configuration
  @EnableAutoConfiguration
  @Import(ReplayController.class)
  static class Config {
    /** A trivial verifier: the signature is valid iff it equals "good". */
    @Bean
    RequestSignatureVerifier requestSignatureVerifier() {
      return request -> "good".equals(request.signature());
    }
  }
}

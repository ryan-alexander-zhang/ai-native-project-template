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
 * With nonce <em>dedup</em> switched off, a nonce that the caller signed still reaches the
 * verifier.
 *
 * <p>The regression this pins down (issue-00165's sibling, issue-00162): the filter used to read
 * the nonce header only when dedup was enabled, and hand the verifier {@code null} otherwise. Any
 * scheme that binds the nonce into the signed string — which this library recommends, because
 * binding it is what stops a captured body being replayed with a fresh timestamp — then computed a
 * different digest from the sender's and rejected <em>every genuine request</em>. Worse than the
 * outage was the diagnosis: the rejection says "Invalid signature", so it reads as an attacker
 * rather than as a switch that was turned off.
 *
 * <p>The verifier below is the smallest thing that models a nonce-bound scheme: the signature is
 * valid iff it equals {@code sig:<nonce>}. That is the whole difference from {@code
 * ReplayProtectionFilterTest}, whose verifier ignores the nonce and therefore could never have seen
 * this.
 */
@SpringBootTest(
    classes = ReplayProtectionNonceBindingTest.Config.class,
    properties = {
      "aipersimmon.ddd.web.replay.enabled=true",
      // Off: this deployment does not want a nonce table (its upstream is idempotent, or it accepts
      // the replay risk inside the timestamp window). It still signs the nonce.
      "aipersimmon.ddd.web.replay.nonce.enabled=false"
    })
@AutoConfigureMockMvc
class ReplayProtectionNonceBindingTest {

  @Autowired MockMvc mvc;

  private static String now() {
    return Long.toString(System.currentTimeMillis() / 1000);
  }

  /** The regression: dedup off, nonce signed, request genuine — and it passes. */
  @Test
  void anonceBoundSignatureVerifiesWhenDedupIsOff() throws Exception {
    mvc.perform(
            post("/replay")
                .header("X-Signature", "sig:n-1")
                .header("X-Timestamp", now())
                .header("X-Nonce", "n-1"))
        .andExpect(status().isOk());
  }

  /**
   * And the flag still means what it is named after: with dedup off, the same nonce twice is
   * accepted twice.
   *
   * <p>This is the assertion that keeps the fix honest. Reading the header unconditionally must not
   * quietly turn dedup back on — a deployment that switched it off did so to avoid the table, and
   * would otherwise be paying for a guard it cannot see.
   */
  @Test
  void thesameNonceIsAcceptedTwiceBecauseDedupIsOff() throws Exception {
    for (int attempt = 0; attempt < 2; attempt++) {
      mvc.perform(
              post("/replay")
                  .header("X-Signature", "sig:n-repeat")
                  .header("X-Timestamp", now())
                  .header("X-Nonce", "n-repeat"))
          .andExpect(status().isOk());
    }
  }

  /**
   * A scheme that sends no nonce at all is unchanged — the verifier still sees {@code null}.
   *
   * <p>Zero behaviour change for the existing deployments this fix must not disturb: Stripe and
   * Slack sign {@code timestamp.body} and send no nonce header, and with dedup off they are not
   * required to (only {@code checkHeaders} demands one, and only when dedup is on).
   */
  @Test
  void aschemeWithNoNonceHeaderIsUnaffected() throws Exception {
    mvc.perform(post("/replay").header("X-Signature", "sig:null").header("X-Timestamp", now()))
        .andExpect(status().isOk());
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
    /** A nonce-bound scheme, minimally: the signature must be {@code sig:<nonce>}. */
    @Bean
    RequestSignatureVerifier requestSignatureVerifier() {
      return request -> ("sig:" + request.nonce()).equals(request.signature());
    }
  }
}

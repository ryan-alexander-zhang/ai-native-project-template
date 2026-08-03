package com.example.samples.s02.ordering.interfaces;

import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A third party's callback. Signed, timestamped and nonced — the protections are configured to cover
 * {@code /webhooks/*} and nothing else, because requiring a signature on the whole application would
 * make every other endpoint unusable to an ordinary client.
 *
 * <p>Handling the callback properly — translating it, making the effect idempotent, reconciling when
 * it never arrives — is S7. Here it only has to prove that a request which reaches this method was
 * authentic and fresh.
 */
@RestController
@RequestMapping("/webhooks")
class PaymentWebhookController {

  @PostMapping("/payment")
  Map<String, String> payment(@RequestBody Map<String, Object> callback) {
    return Map.of("accepted", String.valueOf(callback.get("paymentId")));
  }
}

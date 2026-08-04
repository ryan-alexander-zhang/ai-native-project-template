package com.example.samples.s07.payments.infrastructure.gateway;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.example.samples.s07.payments.application.EscalatePayment;
import com.example.samples.s07.payments.application.RecordGatewayResult;
import com.example.samples.s07.payments.application.RecordGatewayResult.Channel;
import com.example.samples.s07.payments.domain.GatewayOutcome;
import com.example.samples.s07.payments.domain.SettlementOutcome;
import com.example.samples.s07.payments.infrastructure.gateway.GatewayMessages.ChargeNotification;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The provider's way in — and the one class in this sample that is a {@code @RestController} outside the
 * {@code interfaces} package.
 *
 * <p><strong>Why it lives in {@code infrastructure.gateway}.</strong> A callback endpoint is not part of
 * our API; it is the return path of an outbound call. Its URL, its authentication scheme, its payload and
 * its redelivery behaviour are all specified by the provider, and if we changed providers tomorrow this
 * endpoint would not be modified — it would disappear. That is the definition of an adapter, and keeping it
 * next to the outbound client is what allows the code table
 * ({@link GatewayResultCodes}) and the wire records ({@link GatewayMessages}) to be package-private, so
 * "which classes know that {@code 51} means declined" is answered by the compiler. The alternative — the
 * controller in {@code interfaces} — forces either a duplicated code table or a dependency from the entry
 * layer into infrastructure, and the first of those drifts.
 *
 * <p><strong>Everything it answers is 2xx, and that is a decision.</strong> A provider redelivers until it
 * gets a success, so a non-2xx is a request to be told again — useful when we could not process the
 * message for a reason that might pass, and harmful otherwise. Here:
 *
 * <ul>
 *   <li>A settled, duplicated, superseded or contradicted notification → <strong>200</strong>. All four
 *       are handled; three of them are the normal consequence of an at-least-once, unordered channel.
 *   <li>A result code we cannot map → <strong>200</strong>, after flagging the payment for review. Asking
 *       for redelivery would only produce the same unknown code again; we have taken responsibility for it
 *       locally instead.
 *   <li>A reference we have no payment for → <strong>404</strong>, and deliberately not 200. It means the
 *       callback URL is shared with another environment or the notification is for someone else's
 *       transaction, and both are configuration mistakes a person has to see. There is no race here that
 *       could cause it: the charge request only leaves this service after the payment row has committed.
 * </ul>
 *
 * <p><strong>What it does not do.</strong> It does not touch a repository, and it does not decide anything
 * about the payment: it translates a foreign message into one of two commands and returns what the write
 * side said. Both commands go through the bus, so the callback path gets the same transaction boundary,
 * the same validation and the same interceptor chain as an HTTP request from a customer — which is the
 * reason a third-party callback needs no separate story about consistency.
 *
 * <p>Authenticity is not this class's business either. By the time a request reaches it, the library's
 * {@code ReplayProtectionFilter} has verified the signature, checked the timestamp is inside the tolerance
 * window and rejected a nonce it has seen before — see {@code application.yaml} for the four properties
 * that turn that on, and {@link GatewayCallbackSignatureVerifier} for the one bean that has no default.
 */
@RestController
class GatewayCallbackController {

  private static final Logger log = LoggerFactory.getLogger(GatewayCallbackController.class);

  private final CommandBus commandBus;

  GatewayCallbackController(CommandBus commandBus) {
    this.commandBus = commandBus;
  }

  @PostMapping("/gateway-callbacks/charges")
  ResponseEntity<Map<String, String>> charged(@RequestBody ChargeNotification notification) {
    Optional<GatewayOutcome> outcome = GatewayResultCodes.translate(notification.resultCode());

    if (outcome.isEmpty()) {
      log.warn(
          "gateway notification {} for payment {} carries result_code '{}' ({}) which this service"
              + " cannot interpret; flagging for review",
          notification.eventId(),
          notification.merchantRef(),
          notification.resultCode(),
          notification.resultDesc());
      commandBus.send(
          new EscalatePayment(
              notification.merchantRef(),
              "the gateway reported result_code '" + notification.resultCode() + "', which this"
                  + " service cannot interpret"));
      return ResponseEntity.ok(Map.of("status", "ESCALATED"));
    }

    SettlementOutcome settlement =
        commandBus.send(
            new RecordGatewayResult(
                notification.merchantRef(),
                outcome.get(),
                notification.txnRef(),
                Channel.CALLBACK));
    return ResponseEntity.ok(Map.of("status", settlement.name()));
  }
}

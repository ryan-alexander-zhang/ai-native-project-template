package com.example.samples.s07.payments.application;

import com.aipersimmon.ddd.cqrs.Command;
import com.example.samples.s07.payments.domain.GatewayOutcome;
import com.example.samples.s07.payments.domain.SettlementOutcome;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * What the gateway said about a payment, in our vocabulary, from whichever direction we heard it.
 *
 * <p>Two properties of this record carry most of the sample's design.
 *
 * <p><strong>It carries {@link GatewayOutcome}, not a result code.</strong> By the time a command
 * exists, the provider's vocabulary is gone: the translation happens in the adapter, before the bus. So
 * the entire write side — validation, interceptors, handler, aggregate — is expressible without knowing
 * that {@code 51} means declined, and adding a provider means adding an adapter and touching nothing
 * else.
 *
 * <p><strong>There is one of it, not two.</strong> The webhook and the reconciler produce the same
 * command; {@link #channel()} records only which road it came by. Two settlement paths would be two
 * implementations of the same rule, and the second one is always the one with the bug — it runs a
 * hundred times less often.
 */
public record RecordGatewayResult(
    @NotBlank String paymentId,
    @NotNull GatewayOutcome outcome,
    String gatewayRef,
    @NotNull Channel channel)
    implements Command<SettlementOutcome> {

  /**
   * How this news reached us. Not used by any rule — deliberately — but worth recording: the ratio of
   * the two is the health of the webhook, and a service that cannot tell them apart has no way to
   * notice that its callbacks stopped arriving three days ago.
   */
  public enum Channel {
    /** The provider called us. */
    CALLBACK,
    /** We asked. */
    RECONCILIATION
  }
}

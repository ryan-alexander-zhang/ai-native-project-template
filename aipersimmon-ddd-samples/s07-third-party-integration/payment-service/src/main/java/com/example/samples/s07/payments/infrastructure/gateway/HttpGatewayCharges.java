package com.example.samples.s07.payments.infrastructure.gateway;

import com.example.samples.s07.payments.application.GatewayCharges;
import com.example.samples.s07.payments.application.GatewayReport;
import com.example.samples.s07.payments.domain.GatewayOutcome;
import com.example.samples.s07.payments.infrastructure.gateway.GatewayMessages.ChargeStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The pull channel's adapter: four HTTP situations in, four domain answers out.
 *
 * <table>
 *   <caption>the translation, and it is the whole class</caption>
 *   <tr><th>the provider does</th><th>this returns</th><th>because</th></tr>
 *   <tr><td>200 with a code we map</td><td>{@code Reported}</td><td>an answer</td></tr>
 *   <tr><td>200 with a code we do not map</td><td>{@code Unintelligible}</td>
 *       <td>silence is safer than a guess</td></tr>
 *   <tr><td>404</td><td>{@code NoRecord}</td>
 *       <td>a distinct fact: they have never heard of it</td></tr>
 *   <tr><td>timeout, 5xx, connection refused</td><td>{@code Unreachable}</td>
 *       <td>about now, not about the payment</td></tr>
 * </table>
 *
 * <p>The 404 branch is the one that earns its keep. Collapsing it into "unreachable" would leave a payment
 * the provider never received cycling through reconciliation forever; collapsing it into {@code FAILED}
 * would mark charges as refused on the strength of a routing mistake. It is neither, and the only correct
 * automatic action is to tell a human.
 *
 * <p>No retry here, unlike S6's synchronous adapter. This runs inside a reconciliation round that already
 * repeats on a schedule, so a failed poll is retried by construction a minute later — and a retry loop
 * inside a retry loop multiplies attempts against a provider that may be struggling.
 */
@Component
class HttpGatewayCharges implements GatewayCharges {

  private final RestClient gateway;

  HttpGatewayCharges(RestClient gatewayClient) {
    this.gateway = gatewayClient;
  }

  @Override
  public GatewayReport reportFor(String paymentId) {
    try {
      ChargeStatus status =
          gateway
              .get()
              .uri("/charges/{merchantRef}", paymentId)
              .retrieve()
              .body(ChargeStatus.class);
      if (status == null) {
        return new GatewayReport.Unintelligible("the provider answered 200 with no body");
      }
      return GatewayResultCodes.translate(status.resultCode())
          .<GatewayReport>map(
              outcome -> new GatewayReport.Reported(outcome, status.txnRef()))
          .orElseGet(
              () ->
                  new GatewayReport.Unintelligible(
                      "unmapped result_code '"
                          + status.resultCode()
                          + "' ("
                          + status.resultDesc()
                          + ")"));
    } catch (HttpClientErrorException.NotFound absent) {
      return new GatewayReport.NoRecord();
    } catch (RestClientException unreachable) {
      return new GatewayReport.Unreachable(unreachable.getMessage());
    }
  }
}

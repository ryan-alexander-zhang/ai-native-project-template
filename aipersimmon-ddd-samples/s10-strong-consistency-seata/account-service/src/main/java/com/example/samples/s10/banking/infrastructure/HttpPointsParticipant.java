package com.example.samples.s10.banking.infrastructure;

import com.aipersimmon.ddd.tenancy.TenantContext;
import com.example.samples.s10.banking.application.PointsParticipant;
import java.util.Map;
import org.apache.seata.core.context.RootContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * The AT participant over HTTP, and the two headers that make it one.
 *
 * <p><strong>{@code TX_XID} is the whole mechanism.</strong> Seata's global transaction is a thread-local
 * on this side and a thread-local on the other side; nothing connects them except this header. Forget it and
 * every part of the system still works — the debit commits, the award commits, the request succeeds — right
 * up until the first rollback, which then undoes the debit and leaves the points. There is no error, no log
 * line and no test that fails, which is why the participant refuses to write without it rather than
 * trusting callers to remember.
 *
 * <p><strong>{@code X-Tenant-Id} is separate, and has to be.</strong> A global transaction propagates the
 * transaction and nothing else: it does not carry the caller's identity, tenant, locale or trace. Two
 * different pieces of ambient context, two headers, two failure modes — and only one of them is Seata's
 * problem.
 */
@Component
class HttpPointsParticipant implements PointsParticipant {

  private static final Logger log = LoggerFactory.getLogger(HttpPointsParticipant.class);

  private final RestClient pointsClient;

  HttpPointsParticipant(RestClient pointsClient) {
    this.pointsClient = pointsClient;
  }

  @Override
  public boolean award(String reference, String accountId, int points) {
    String xid = RootContext.getXID();
    try {
      Map<?, ?> response =
          pointsClient
              .post()
              .uri("/awards")
              .contentType(MediaType.APPLICATION_JSON)
              .header(RootContext.KEY_XID, xid)
              .header("X-Tenant-Id", TenantContext.effective().value())
              .body(Map.of("reference", reference, "accountId", accountId, "points", points))
              .retrieve()
              .body(Map.class);
      return response != null
          && ("AWARDED".equals(response.get("outcome"))
              || "ALREADY_AWARDED".equals(response.get("outcome")));
    } catch (RestClientResponseException e) {
      // A refusal is an answer, and the answer is "do not commit". Returning false rather than letting the
      // exception fly keeps the decision in the use case, where the rollback reason is written.
      log.warn(
          "points service refused reference {} with {}: {}",
          reference,
          e.getStatusCode(),
          e.getResponseBodyAsString());
      return false;
    }
  }
}

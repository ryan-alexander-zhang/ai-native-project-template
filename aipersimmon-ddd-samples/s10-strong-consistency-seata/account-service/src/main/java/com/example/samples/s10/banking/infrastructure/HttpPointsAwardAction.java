package com.example.samples.s10.banking.infrastructure;

import com.aipersimmon.ddd.tenancy.TenantContext;
import com.aipersimmon.ddd.tenancy.Tenants;
import com.example.samples.s10.banking.application.PointsAwardAction;
import java.util.Map;
import org.apache.seata.rm.tcc.api.BusinessActionContext;
import org.apache.seata.rm.tcc.api.BusinessActionContextParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * The TCC participant over HTTP: three calls, and three things that are easy to get wrong.
 *
 * <p><strong>1. No XID header, deliberately.</strong> The opposite of the AT client one file over. A TCC
 * branch is registered here, by Seata, when {@link #tryAward} is invoked; the remote calls are ordinary
 * business requests that must each commit on their own. Propagating the XID would additionally register an
 * <em>AT</em> branch for the same write in the other service, so a rollback would try to undo it twice by
 * two mechanisms that disagree — and Try's write is one the model is supposed to keep until Cancel says
 * otherwise. The participant refuses an XID for exactly this reason.
 *
 * <p><strong>2. Confirm and Cancel run on Seata's threads.</strong> Not the request thread — that one
 * returned to the customer already. So there is no bound tenant, no security context and no request-scoped
 * anything; the tenant has to come out of {@link BusinessActionContext}, which is why {@code tryAward}
 * annotates it as a parameter. This is the concrete cost of TCC that no diagram shows.
 *
 * <p><strong>3. Both phases must return true only when they are genuinely settled.</strong> Returning false
 * makes Seata retry, which is correct for a transient failure and a livelock for a permanent one. Returning
 * true when nothing happened silently drops the compensation. So each phase reports the participant's own
 * outcome vocabulary rather than an HTTP status.
 */
@Component
class HttpPointsAwardAction implements PointsAwardAction {

  private static final Logger log = LoggerFactory.getLogger(HttpPointsAwardAction.class);

  private final RestClient pointsClient;

  HttpPointsAwardAction(RestClient pointsClient) {
    this.pointsClient = pointsClient;
  }

  @Override
  public boolean tryAward(
      BusinessActionContext context,
      @BusinessActionContextParameter("reference") String reference,
      @BusinessActionContextParameter("accountId") String accountId,
      @BusinessActionContextParameter("points") int points,
      @BusinessActionContextParameter("tenant") String tenant) {
    try {
      Map<?, ?> response =
          pointsClient
              .post()
              .uri("/reservations")
              .contentType(MediaType.APPLICATION_JSON)
              .header("X-Tenant-Id", tenant)
              .body(Map.of("reference", reference, "accountId", accountId, "points", points))
              .retrieve()
              .body(Map.class);
      String outcome = response == null ? "NONE" : String.valueOf(response.get("outcome"));
      return "RESERVED".equals(outcome) || "ALREADY_RESERVED".equals(outcome);
    } catch (RestClientResponseException e) {
      log.warn("points reservation refused for {}: {}", reference, e.getResponseBodyAsString());
      return false;
    }
  }

  @Override
  public boolean confirmAward(BusinessActionContext context) {
    return settle(context, "confirm");
  }

  @Override
  public boolean cancelAward(BusinessActionContext context) {
    return settle(context, "cancel");
  }

  private boolean settle(BusinessActionContext context, String phase) {
    String reference = String.valueOf(context.getActionContext("reference"));
    String accountId = String.valueOf(context.getActionContext("accountId"));
    int points = Integer.parseInt(String.valueOf(context.getActionContext("points")));
    String tenant = String.valueOf(context.getActionContext("tenant"));

    // Rebind the tenant for the duration of this phase. Nothing else will: this thread belongs to Seata.
    return TenantContext.runAs(
        Tenants.of(tenant),
        () -> {
          try {
            Map<?, ?> response =
                pointsClient
                    .post()
                    .uri("/reservations/{reference}/" + phase, reference)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Tenant-Id", tenant)
                    .body(Map.of("accountId", accountId, "points", points))
                    .retrieve()
                    .body(Map.class);
            String outcome = response == null ? "NONE" : String.valueOf(response.get("outcome"));
            log.info("TCC {} for {} returned {}", phase, reference, outcome);
            return "SETTLED".equals(outcome)
                || "ALREADY_SETTLED".equals(outcome)
                || "ALREADY_CANCELLED".equals(outcome)
                // Cancel with nothing to cancel is Seata's empty rollback, and it is done: the
                // participant has written the mark that refuses a late Try.
                || ("cancel".equals(phase) && "NOTHING_TO_SETTLE".equals(outcome));
          } catch (RestClientResponseException e) {
            log.warn(
                "TCC {} for {} failed with {}; Seata will retry",
                phase,
                reference,
                e.getStatusCode());
            return false;
          }
        });
  }
}

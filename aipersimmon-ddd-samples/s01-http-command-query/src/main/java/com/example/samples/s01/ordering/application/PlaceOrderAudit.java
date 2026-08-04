package com.example.samples.s01.ordering.application;

import com.aipersimmon.ddd.operationlog.definition.OperationLogDefinition;
import com.aipersimmon.ddd.operationlog.definition.PreparedOperationLog;
import com.aipersimmon.ddd.operationlog.model.ClassifiedFailure;
import com.aipersimmon.ddd.operationlog.model.OperationLogDraft;
import com.aipersimmon.ddd.operationlog.model.OperationLogInvocation;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The other capture path: a type-safe {@link OperationLogDefinition} instead of an annotation.
 *
 * <p><strong>Not a preference — a necessity here.</strong> The annotation's {@code targetId} template is
 * compiled against one root, {@code input}, on both the success and failure paths. A create mints its
 * identity inside the handler, so at annotation time there is nothing to point at: {@code
 * ${input.customerId}} would compile, boot, and quietly file every order operation under a customer id,
 * which is the kind of wrong that only surfaces when somebody audits the wrong record. This definition
 * reads the id from the <em>result</em>, which the annotation cannot see.
 *
 * <p>Two more things this path can do that the annotation cannot: capture a before projection (it runs
 * inside the business transaction, before the handler) and record a {@code changes} list. Neither is used
 * here, because a create has no before-state and nothing changed from anything — which is worth saying,
 * since "use the Definition when you need before/after" is the usual summary and it under-sells the reason
 * this particular command needs it.
 *
 * <p>The cost of the path is visible in the file: what the annotation says in six lines takes a class, and
 * the operation code and target type are no longer next to the command they describe.
 */
@Component
class PlaceOrderAudit implements OperationLogDefinition<PlaceOrder, String> {

  static final String CODE = "ordering.order.place";

  @Override
  public PreparedOperationLog<String> prepare(PlaceOrder input, OperationLogInvocation invocation) {
    // Runs before the handler and inside its transaction. Nothing to read: this is a create.
    return orderId ->
        Optional.of(
            OperationLogDraft.from(invocation)
                .operation(CODE)
                .target("Order", orderId, null)
                .succeeded()
                .summary(
                    "Placed order "
                        + orderId
                        + " for customer "
                        + input.customerId()
                        + " with "
                        + input.lines().size()
                        + " line(s)")
                // The count, not the lines. An audit row is not a copy of the request: a full payload
                // makes the log the largest table in the schema, puts whatever the request happened to
                // contain under a retention policy written for audit data, and answers a question
                // ("what exactly was submitted") that belongs to the request log if it belongs anywhere.
                // OrderLineCountIsRecordedButNotTheLines asserts the absence.
                .detail("lineCount", String.valueOf(input.lines().size()))
                .build());
  }

  /**
   * Records nothing, and that is the interesting half.
   *
   * <p>A failed create has no target. {@link com.aipersimmon.ddd.operationlog.model.Target} requires a
   * non-null id and the whole point of {@code target_id} is that an auditor can search by it, so the
   * choices are a placeholder that pollutes the one index they use, a different {@code targetType} for the
   * failure than for the success (making the operation unqueryable as a unit), or nothing. This sample
   * chooses nothing, and names the fix rather than hiding the gap: <strong>if failed creates must be
   * audited, the identity has to be minted before the command runs</strong> — client-supplied, as S2 does
   * for idempotency, or stamped at the edge from {@code IdGenerator}. Then the create audits exactly like
   * {@link ConfirmOrder} does.
   *
   * <p>Whether a failure can be audited at all therefore depends on whether the target's identity exists
   * before the operation. That is a modelling consequence of where ids are minted, and it is not obvious
   * until an auditor asks who tried.
   */
  @Override
  public Optional<OperationLogDraft> failed(
      PlaceOrder input, OperationLogInvocation invocation, ClassifiedFailure failure) {
    return Optional.empty();
  }
}

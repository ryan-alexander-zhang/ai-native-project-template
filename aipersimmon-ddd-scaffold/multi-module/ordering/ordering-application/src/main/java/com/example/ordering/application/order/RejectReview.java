package com.example.ordering.application.order;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.operationlog.annotation.OperationLog;

/**
 * Command to reject the manual review of an order held in {@code AWAITING_REVIEW}, cancelling it.
 * The operator counterpart of {@link ApproveReview}: review is a decision, and a decision with only
 * one available answer is not one (issue-00082).
 *
 * <p>Every part this needs already existed and none of it could be reached — {@code
 * CancellationReason.ReviewRejected}, {@code CancellationCategory.REVIEW_REJECTED}, the policy's
 * {@code ensureReviewCancellationAllowed}, and the rejecting form of the review evidence (then a
 * {@code boolean approved} flag no caller had ever set to {@code false}; now the {@code
 * ReviewDecisionRef.Rejection} type, issue-00134). The sealed {@code CancellationReason} forced the
 * domain to handle the branch; nothing forced the application to offer it. No result.
 */
@OperationLog(
    code = "ordering.order.reject-review",
    targetType = "Order",
    targetId = "${input.orderId}",
    success = "Rejected review for order ${input.orderId}",
    failure =
        "Rejecting review for order ${input.orderId} failed: ${failure.code} (${failure.safeSummary})")
public record RejectReview(String orderId) implements Command<Void> {}

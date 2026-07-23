package com.aipersimmon.ddd.archunit.fixture.good.ordering.application;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.operationlog.annotation.OperationLog;

/**
 * The sanctioned use of {@code @OperationLog}: an application-layer {@link Command} carrying the
 * annotation as additive metadata. Accepted by {@link
 * com.aipersimmon.ddd.archunit.OperationLogRules#operationLogShouldOnlyAnnotateApplicationCommands()}.
 */
@OperationLog(
    code = "ordering.confirm",
    targetType = "Order",
    targetId = "${input.orderId}",
    success = "Order ${input.orderId} confirmed")
public record GoodAuditedConfirmOrder(String orderId) implements Command<Void> {}

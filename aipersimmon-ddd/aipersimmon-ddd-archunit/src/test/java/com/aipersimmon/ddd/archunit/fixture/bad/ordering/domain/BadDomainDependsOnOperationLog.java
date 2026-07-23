package com.aipersimmon.ddd.archunit.fixture.bad.ordering.domain;

import com.aipersimmon.ddd.operationlog.annotation.OperationLog;

/**
 * Violates {@link
 * com.aipersimmon.ddd.archunit.OperationLogRules#domainShouldNotDependOnOperationLog()}: a domain
 * type reaching for the Operation Log component. Because it also carries {@code @OperationLog}
 * without being an application-layer command, it is a violation of {@link
 * com.aipersimmon.ddd.archunit.OperationLogRules#operationLogShouldOnlyAnnotateApplicationCommands()}
 * too — a domain object is neither a {@code Command} nor in the application layer.
 */
@OperationLog(code = "ordering.badDomain", targetType = "Order", targetId = "x", success = "y")
public final class BadDomainDependsOnOperationLog {

  private final String orderId;

  public BadDomainDependsOnOperationLog(String orderId) {
    this.orderId = orderId;
  }

  public String orderId() {
    return orderId;
  }
}

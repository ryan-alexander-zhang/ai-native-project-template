package com.aipersimmon.ddd.cqrs.spring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Decides, at startup, what it means for this application to have no transaction manager.
 *
 * <p>The command bus's contract is that one command is one transaction: the aggregate write, the
 * outbox row and the domain events commit together or not at all. That contract is implemented by
 * two beans which are both conditional on a {@code PlatformTransactionManager} — so when none
 * exists they simply are not there, every command runs untransacted, and the guarantee is gone
 * without a single line of output. Consumer code that was written against it keeps compiling and
 * keeps passing its tests; what changes is that a partial failure now leaves partial state.
 *
 * <p>This bean makes that state impossible to reach by accident. It is not a mechanism — nothing
 * calls it at runtime — it is the place where the framework's risk posture for this particular
 * absence is stated once, next to the reason. The same posture the framework already takes for a
 * missing {@code IdGenerator}, which is a startup failure rather than a silent fallback.
 */
public final class CommandTransactionGuard implements InitializingBean {

  private static final Logger log = LoggerFactory.getLogger(CommandTransactionGuard.class);

  private final ObjectProvider<PlatformTransactionManager> transactionManager;
  private final boolean required;

  public CommandTransactionGuard(
      ObjectProvider<PlatformTransactionManager> transactionManager, boolean required) {
    this.transactionManager = transactionManager;
    this.required = required;
  }

  @Override
  public void afterPropertiesSet() {
    if (transactionManager.getIfAvailable() != null) {
      return;
    }
    if (required) {
      throw new MissingTransactionManagerException(
          "the aipersimmon-ddd CQRS starter is on the classpath but no PlatformTransactionManager"
              + " bean exists, so every command would run without a transaction: an aggregate write,"
              + " its outbox row and its domain events would each commit on their own, and a failure"
              + " part-way through would leave the state it already wrote. Add a transaction manager"
              + " (a DataSource gives you one through Spring Boot's auto-configuration), or set"
              + " aipersimmon.ddd.cqrs.transaction.required=false if this application deliberately"
              + " runs without transactions.");
    }
    log.warn(
        "aipersimmon-ddd: no PlatformTransactionManager and"
            + " aipersimmon.ddd.cqrs.transaction.required=false — commands run WITHOUT a"
            + " transaction. An aggregate write, its outbox row and its domain events each commit"
            + " independently, so a partial failure leaves partial state. This is only safe while no"
            + " handler writes to a database.");
  }
}

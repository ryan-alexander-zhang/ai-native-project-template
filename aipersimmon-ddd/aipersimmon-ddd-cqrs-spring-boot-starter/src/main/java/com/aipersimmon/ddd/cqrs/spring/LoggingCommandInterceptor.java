package com.aipersimmon.ddd.cqrs.spring;

import com.aipersimmon.ddd.application.ApplicationException;
import com.aipersimmon.ddd.core.exception.DomainException;
import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Logs the dispatch of each command and whether it succeeded or failed, and puts the command's
 * correlation id on the MDC for the duration of the handler so every log line emitted while
 * handling shares it. Ordered outermost ({@code order = 0}) so it observes the whole chain,
 * including the transaction boundary applied by inner interceptors.
 */
public class LoggingCommandInterceptor implements CommandInterceptor {

  /** Ordered outermost. */
  public static final int ORDER = 0;

  /** MDC key holding the current flow's correlation id. */
  public static final String CORRELATION_ID_MDC_KEY = "correlationId";

  private static final Logger log = LoggerFactory.getLogger(LoggingCommandInterceptor.class);

  @Override
  public <R> R intercept(Command<R> command, CommandContext context, Invocation<R> invocation) {
    String name = command.getClass().getSimpleName();
    String previous = MDC.get(CORRELATION_ID_MDC_KEY);
    MDC.put(CORRELATION_ID_MDC_KEY, context.correlationId());
    try {
      log.debug(
          "Handling command {} [correlationId={}, causationId={}]",
          name,
          context.correlationId(),
          context.causationId());
      R result = invocation.proceed();
      log.debug("Handled command {}", name);
      return result;
    } catch (RuntimeException e) {
      logFailure(name, context, e);
      throw e;
    } finally {
      if (previous == null) {
        MDC.remove(CORRELATION_ID_MDC_KEY);
      } else {
        MDC.put(CORRELATION_ID_MDC_KEY, previous);
      }
    }
  }

  /**
   * Report a failed command at a level that matches what kind of failure it was.
   *
   * <p>Both of these used to be {@code DEBUG}, which meant that under the default INFO threshold a
   * command that failed produced no log line at all — the one outcome an operator most wants to
   * see. Raising everything to WARN is the other wrong answer: a rejected order is an expected
   * outcome of a working system, and a busy reject path would drown the faults worth reading.
   *
   * <p>So the level follows the distinction the framework already draws, in the words its own base
   * types use — {@link DomainException} and {@link ApplicationException} exist "so callers can
   * distinguish business-rule failures from technical faults":
   *
   * <ul>
   *   <li>a business-rule failure is INFO, with its message and no stack — the stack of a rule that
   *       did its job is noise, and the message plus the correlation id on the MDC is what makes it
   *       traceable;
   *   <li>anything else is WARN <em>with</em> the exception, because somebody has to see the stack
   *       and this is the only place that sees every command whatever dispatched it — a relay, a
   *       deadline worker and an HTTP request do not share an outer handler.
   * </ul>
   *
   * <p>Matched by type, never by class name: a name-based match is how an unrelated exception that
   * happens to share a simple name gets classified as something it is not.
   */
  private static void logFailure(String name, CommandContext context, RuntimeException e) {
    if (e instanceof DomainException || e instanceof ApplicationException) {
      log.info(
          "Command {} was rejected: {} [correlationId={}]",
          name,
          e.getMessage(),
          context.correlationId());
      return;
    }
    log.warn(
        "Command {} failed [correlationId={}, causationId={}]",
        name,
        context.correlationId(),
        context.causationId(),
        e);
  }

  @Override
  public int order() {
    return ORDER;
  }
}

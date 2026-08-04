package com.aipersimmon.ddd.core.error;

/**
 * Flattens a throwable and its causes into one line, for the columns an operator reads.
 *
 * <p>This exists because the outermost exception is routinely the least informative one. A
 * transport wraps: Spring Kafka's synchronous send failure arrives as {@code KafkaException: Send
 * failed}, whose message names neither the topic nor the reason — both of which are two levels
 * down, in a {@code TimeoutException} and an {@code UnknownTopicOrPartitionException}. Recording
 * only the top frame turns the one diagnostic column a dead letter has into "it failed", which an
 * operator already knew from the row existing.
 *
 * <p>Bounded on both axes, because this string is written to a column on a failure path and must
 * not itself become a problem: at most {@value #MAX_DEPTH} causes are walked (stopping early on a
 * self-referential cause, which a badly-written exception can produce), and the result is truncated
 * to {@value #MAX_LENGTH} characters. The same bounded-walk shape the failure classifiers use to
 * decide whether a cause is permanent — deciding already reads the chain, so recording should too.
 *
 * <p>Format is {@code class: message <- class: message <- …}, outermost first, because that is the
 * order the frames are related in and reading stops as soon as it is answered.
 */
public final class FailureSummary {

  /** How many causes are walked, including the outermost throwable. */
  public static final int MAX_DEPTH = 10;

  /** Ceiling on the produced string. */
  public static final int MAX_LENGTH = 2000;

  private static final String SEPARATOR = " <- ";

  private FailureSummary() {}

  /**
   * One line describing {@code error} and its causes.
   *
   * @param error the failure; {@code null} yields {@code "null"} rather than throwing, because a
   *     summariser that can fail on a failure path is worse than a useless string
   * @return the flattened description, never null
   */
  public static String of(Throwable error) {
    if (error == null) {
      return "null";
    }
    StringBuilder text = new StringBuilder();
    Throwable cause = error;
    for (int depth = 0; cause != null && depth < MAX_DEPTH; depth++) {
      if (depth > 0) {
        text.append(SEPARATOR);
      }
      text.append(cause.getClass().getName()).append(": ").append(cause.getMessage());
      if (cause.getCause() == cause) {
        break;
      }
      cause = cause.getCause();
    }
    return text.length() > MAX_LENGTH ? text.substring(0, MAX_LENGTH) : text.toString();
  }
}

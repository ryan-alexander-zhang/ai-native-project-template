package com.aipersimmon.ddd.test;

import com.aipersimmon.ddd.inbox.Inbox;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An {@link Inbox} over a set — the official in-memory double (issue-00140). It keeps the
 * contract's one subtle rule: identity is the <strong>pair</strong> {@code (source, messageKey)},
 * so two producers sharing a message id do not collapse into one delivery. No transactionality: a
 * recorded key stays recorded even if the test's work then throws, which is exactly the difference
 * between this double and a database inbox — a test that needs rollback-on-failure semantics is an
 * integration test and should use the real store.
 */
public final class InMemoryInbox implements Inbox {

  private final Set<String> seen = ConcurrentHashMap.newKeySet();

  @Override
  public boolean alreadyProcessed(String source, String messageKey) {
    return !seen.add(source + "\n" + messageKey);
  }

  /** How many distinct {@code (source, messageKey)} pairs have been recorded. */
  public int size() {
    return seen.size();
  }

  /** Forget everything recorded so far. */
  public void reset() {
    seen.clear();
  }
}

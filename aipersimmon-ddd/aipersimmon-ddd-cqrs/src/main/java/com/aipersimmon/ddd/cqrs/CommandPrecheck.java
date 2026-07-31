package com.aipersimmon.ddd.cqrs;

/**
 * A read-only, fail-fast check that runs <em>before</em> a command's transaction is opened.
 *
 * <p>This is the home for the step that otherwise has none: an advisory cross-context query — "can
 * inventory offer these SKUs at all?" — that should reject a hopeless command early but contributes
 * nothing to the transaction's consistency. Placed inside the handler it runs on the handler's
 * first line, which is already inside the write transaction; the moment the port behind it becomes
 * a remote client, that transaction (and the database connection under it) waits on a remote call,
 * and one slow dependency amplifies into an exhausted connection pool (issue-00141). The bus runs
 * prechecks between validation and the transaction interceptor, so a refusal costs no connection at
 * all.
 *
 * <p>Contract: a precheck <strong>reads and refuses, nothing else</strong>. It must not write — any
 * effect it caused would live outside the command's transaction and survive the command's rollback.
 * It refuses by throwing (a domain exception, typically); returning normally lets the dispatch
 * proceed. Prechecks run on <em>every</em> dispatch of their command type, including at-least-once
 * redeliveries via {@code sendAs}, so they must be safe to repeat. And because they run before the
 * transaction, they are advisory by construction: the world may change between the check and the
 * commit, so the invariant they screen for must still be enforced (or compensated) by whatever owns
 * it.
 *
 * <p>Implementations are discovered as beans, matched to their command by the type parameter — the
 * same resolution the bus uses for handlers. Several prechecks may target one command; all of them
 * run, in bean order, and the first refusal wins.
 *
 * @param <C> the command this precheck screens
 */
public interface CommandPrecheck<C extends Command<?>> {

  /**
   * Screens {@code command} before its transaction opens. Throw to refuse the dispatch; return to
   * let it proceed.
   */
  void check(C command, CommandContext context);
}

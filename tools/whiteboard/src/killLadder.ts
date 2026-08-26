/**
 * How long a stopped process gets on the polite signal before the unignorable
 * one follows (issue-00012). Seconds, not tens of them: long enough for a CLI to
 * hear it and write out what it was holding, short enough that a Stop the user
 * is waiting on still answers like a button (spec-00001-AC-49.10).
 */
export const KILL_GRACE_MS = 3_000

/**
 * The signal escalation both spawn seams end a process with (design-00001 §10.3).
 * The first rung differs — SIGHUP is what a pty hangs up with, SIGTERM what a
 * child process with no terminal is asked with — and everything below it is the
 * same: the polite signal, then SIGKILL once the grace is up, which is what makes
 * waiting for the exit bounded.
 *
 * The escalation is armed once: asking twice must not restart the clock, and it
 * is unreffed so a board is never held open by a process it has already
 * signalled.
 */
export function killLadder(send: (signal: string) => void, first: string, graceMs: number = KILL_GRACE_MS) {
  let escalation: NodeJS.Timeout | undefined
  return {
    kill: () => {
      send(first)
      escalation ??= setTimeout(() => send('SIGKILL'), graceMs).unref()
    },
    /** The process is gone, so nothing is left ticking over it. */
    settle: () => clearTimeout(escalation),
  }
}

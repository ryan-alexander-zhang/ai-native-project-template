/**
 * One turn at a time, per key. Two places need exactly this and nothing more:
 * every board commit runs in one queue so two of them cannot stage each other's
 * changes (spec-00003-FR-8, design-00001 §4), and every read-modify-write of a
 * document's ask list runs in that document's own queue so two threads of it
 * cannot write over each other's wrap-up (spec-00005-AC-6.3,
 * design-00001 §10.2). The two are independent queues of the same shape — the
 * commit queue deliberately knows nothing of asks — which is why the shape is
 * here and the keys are the callers'.
 *
 * A rejected turn is its caller's to read and never the next turn's reason not
 * to run, which is why the chain is kept on a swallowed copy of it.
 */
export class SerialQueue {
  private readonly tails = new Map<string, Promise<unknown>>()

  run<T>(key: string, turn: () => T | Promise<T>): Promise<T> {
    const running = (this.tails.get(key) ?? Promise.resolve()).then(turn)
    this.tails.set(
      key,
      running.then(
        () => {},
        () => {},
      ),
    )
    return running
  }
}

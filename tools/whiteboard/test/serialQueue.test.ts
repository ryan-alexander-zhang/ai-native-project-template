import { describe, expect, it } from 'vitest'
import { SerialQueue } from '../src/serialQueue.ts'

/** A turn that finishes when the test says so, recording when it ran and ended. */
function gate(log: string[], name: string) {
  let release!: () => void
  const held = new Promise<void>((resolve) => {
    release = resolve
  })
  return {
    release,
    turn: async () => {
      log.push(`${name}:start`)
      await held
      log.push(`${name}:end`)
      return name
    },
  }
}

/** How many keys the queue is still carrying work for — its own map, read for this one case. */
const keys = (queue: SerialQueue) => queue['tails'].size

/** A turn is chained onto the key's tail, so it begins a tick after it is handed over. */
const flush = () => new Promise((resolve) => setImmediate(resolve))

describe('SerialQueue', () => {
  /**
   * The property both callers stand on (spec-00003-FR-8, spec-00005-AC-6.3): a
   * turn does not begin until the one before it on that key has ended.
   */
  it('runs one turn at a time on a key, in arrival order', async () => {
    const log: string[] = []
    const queue = new SerialQueue()
    const first = gate(log, 'first')
    const second = gate(log, 'second')

    const running = [queue.run('k', first.turn), queue.run('k', second.turn)]
    await flush()
    expect(log).toEqual(['first:start'])

    first.release()
    second.release()

    expect(await Promise.all(running)).toEqual(['first', 'second'])
    expect(log).toEqual(['first:start', 'first:end', 'second:start', 'second:end'])
  })

  /** Different keys are different queues: one document's list never waits on another's. */
  it('runs turns on different keys at the same time', async () => {
    const log: string[] = []
    const queue = new SerialQueue()
    const here = gate(log, 'here')
    const there = gate(log, 'there')

    const running = [queue.run('a', here.turn), queue.run('b', there.turn)]
    await flush()
    expect(log).toEqual(['here:start', 'there:start'])

    here.release()
    there.release()
    await Promise.all(running)
  })

  /**
   * A rejected turn is its caller's to read and never the next turn's reason not
   * to run — a refused submit must not wedge that document's queue for good.
   */
  it('goes on to the next turn after one rejects, and hands the rejection to its own caller', async () => {
    const queue = new SerialQueue()

    const refused = queue.run('k', () => {
      throw new Error('refused')
    })

    await expect(refused).rejects.toThrowError('refused')
    expect(await queue.run('k', () => 'after')).toBe('after')
  })

  /**
   * The keys are their callers' — a document id, and there is no telling how many
   * documents a long-running board will touch — so a key whose chain has drained
   * is let go of rather than kept for ever. What is still queued is never
   * dropped: the second turn below holds the key while the first one's own
   * cleanup runs.
   */
  it('lets a key go once its chain has drained, and keeps one that is still queued', async () => {
    const log: string[] = []
    const queue = new SerialQueue()
    const held = gate(log, 'held')

    const running = [queue.run('k', held.turn), queue.run('k', () => 'after')]
    await flush()
    expect(keys(queue)).toBe(1)

    held.release()
    await Promise.all(running)
    await flush()

    expect(keys(queue)).toBe(0)
    // And a key used again after that is a key back in the map, then out again.
    const next = queue.run('k', () => 'again')
    expect(keys(queue)).toBe(1)
    expect(await next).toBe('again')
    await flush()
    expect(keys(queue)).toBe(0)
  })
})

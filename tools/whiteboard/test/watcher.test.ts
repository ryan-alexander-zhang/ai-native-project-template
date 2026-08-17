import { writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { DEBOUNCE_MS, DocsWatcher } from '../src/watcher.ts'
import { armWatch, makeDocsDir } from './helpers.ts'

const watchers: DocsWatcher[] = []
const SIGNAL_WAIT = { timeout: 10_000, interval: 25 }

async function watching() {
  const docsDir = makeDocsDir({ 'idea/a.md': '# Idea\n' })
  const watcher = new DocsWatcher(docsDir)
  watchers.push(watcher)
  watcher.start()
  await armWatch(watcher, docsDir)
  return { docsDir, watcher }
}

afterEach(async () => {
  await Promise.all(watchers.splice(0).map((watcher) => watcher.close()))
})

/** The mechanism behind spec-00001-FR-42; what it means for a board is in server.test.ts. */
describe('DocsWatcher', () => {
  it('counts the boards following it, and none is a number like any other', async () => {
    const { watcher } = await watching()
    expect(watcher.followers).toBe(0)

    const stop = watcher.subscribe(() => {})
    expect(watcher.followers).toBe(1)

    stop()
    expect(watcher.followers).toBe(0)
  })

  it('watches once however many times it is started', async () => {
    const { docsDir, watcher } = await watching()
    let signals = 0
    watcher.subscribe(() => {
      signals += 1
    })

    watcher.start()
    watcher.start()
    writeFileSync(join(docsDir, 'idea/b.md'), '# Another\n')

    await vi.waitFor(() => expect(signals).toBe(1), SIGNAL_WAIT)
    await new Promise((resolve) => setTimeout(resolve, 4 * DEBOUNCE_MS))
    expect(signals).toBe(1)
  })

  it('says nothing more once it is closed, and closes only once', async () => {
    const { docsDir, watcher } = await watching()
    let signals = 0
    watcher.subscribe(() => {
      signals += 1
    })

    await watcher.close()
    await watcher.close()
    writeFileSync(join(docsDir, 'idea/b.md'), '# Another\n')
    await new Promise((resolve) => setTimeout(resolve, 4 * DEBOUNCE_MS))

    expect(signals).toBe(0)
    expect(watcher.followers).toBe(0)
  })
})

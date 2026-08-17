import { type FSWatcher, watch } from 'chokidar'

/**
 * The window a burst of writes is folded into (spec-00001-AC-42.4). An agent
 * saving a handful of files in a row is one change as far as a board is
 * concerned; the ceiling is FR-42's «visible within a second», and this sits an
 * order of magnitude under it (design-00001 §6).
 */
export const DEBOUNCE_MS = 100

/**
 * Watches the docs tree and tells every connected board that it moved
 * (spec-00001-FR-42). The signal carries nothing: a board that hears it re-reads
 * from the API, so there is never a second copy of the graph to keep in step,
 * and a create-then-delete burst converges on whatever the disk ends up holding
 * (design-00001 §6). It watches nothing but `docsDir`, which is the whole of why
 * a change elsewhere in the repo cannot signal (AC-42.5).
 */
export class DocsWatcher {
  private readonly docsDir: string
  private readonly debounceMs: number
  private readonly listeners = new Set<() => void>()
  private watcher?: FSWatcher
  private timer?: ReturnType<typeof setTimeout>
  private scanned = Promise.resolve()

  constructor(docsDir: string, debounceMs = DEBOUNCE_MS) {
    this.docsDir = docsDir
    this.debounceMs = debounceMs
  }

  start(): void {
    if (this.watcher) return
    this.watcher = watch(this.docsDir, { ignoreInitial: true })
    this.scanned = new Promise((resolve) => this.watcher?.once('ready', () => resolve()))
    this.watcher.on('all', () => this.schedule())
    // A watch that breaks costs the boards their live updates, not their board:
    // every other path into the docs still works (spec-00001-FR-43's spirit).
    this.watcher.on('error', () => {})
  }

  /**
   * Resolves once the first scan is done, which is the moment writes start being
   * seen. Nothing but a caller that must not miss the very next write needs it.
   */
  ready(): Promise<void> {
    return this.scanned
  }

  /** How many boards are following the signal — nought is a normal number (spec-00001-AC-42.8). */
  get followers(): number {
    return this.listeners.size
  }

  /** Follow the signal until the returned function is called. */
  subscribe(listener: () => void): () => void {
    this.listeners.add(listener)
    return () => {
      this.listeners.delete(listener)
    }
  }

  async close(): Promise<void> {
    clearTimeout(this.timer)
    this.timer = undefined
    await this.watcher?.close()
    this.watcher = undefined
    this.listeners.clear()
  }

  private schedule(): void {
    clearTimeout(this.timer)
    this.timer = setTimeout(() => {
      this.timer = undefined
      // Nobody listening is not an error — the docs change whether or not a
      // board is open (spec-00001-AC-42.8).
      for (const listener of [...this.listeners]) listener()
    }, this.debounceMs)
    // The board is not kept alive by a change nobody asked for.
    this.timer.unref?.()
  }
}

/** How long the board waits before dialling again, and the ceiling it walks up to (design-00001 §6). */
export const FIRST_RETRY_MS = 1_000
export const MAX_RETRY_MS = 30_000

export interface EventLink {
  close: () => void
}

/**
 * The docs-change channel (spec-00001-FR-42). Every frame is the same bare
 * signal — «something under docs/ moved» — and so is every connection, which is
 * how the board catches up on what changed while it was not listening
 * (spec-00001-AC-43.2).
 *
 * A channel that drops, or was never there, is silent rather than wrong: the
 * board keeps working off what it has and dials again on a widening delay, and
 * nothing about the failure reaches the user (spec-00001-FR-43, design-00002 §10).
 */
export function connectEvents(onChange: () => void): EventLink {
  const url = `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/api/events`
  let socket: WebSocket | undefined
  let retryIn = FIRST_RETRY_MS
  let timer: ReturnType<typeof setTimeout> | undefined
  let given = false

  function dial() {
    try {
      socket = new WebSocket(url)
    } catch {
      // Not even being able to dial is the disconnected case, not a new one.
      retry()
      return
    }
    socket.addEventListener('open', () => {
      retryIn = FIRST_RETRY_MS
      onChange()
    })
    socket.addEventListener('message', () => onChange())
    // Nothing to report and nothing to do: the close that follows retries.
    socket.addEventListener('error', () => {})
    socket.addEventListener('close', retry)
  }

  function retry() {
    if (given) return
    // At most one dial is ever pending: a second close while one is waiting must
    // not leave a timer nobody holds, or closing the channel cannot stop it.
    clearTimeout(timer)
    timer = setTimeout(dial, retryIn)
    retryIn = Math.min(retryIn * 2, MAX_RETRY_MS)
  }

  dial()
  return {
    close: () => {
      given = true
      clearTimeout(timer)
      socket?.close()
    },
  }
}

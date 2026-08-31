export interface TerminalLink {
  send: (data: string) => void
  /**
   * The terminal's own size, sent as a binary frame so no keystroke can be read
   * as a size and no size typed at the agent (spec-00001-FR-12, issue-00009).
   */
  resize: (cols: number, rows: number) => void
  close: () => void
}

/**
 * One session's terminal channel, named by its id — a channel per session, so
 * output and keystrokes cannot cross between them (spec-00003-FR-1, FR-5). The
 * socket carries that session's replayed buffer as its first frame, so a
 * reconnecting board picks up where it left off (spec-00001-FR-21).
 */
export function connectTerminal(sessionId: string, onData: (data: string) => void): TerminalLink {
  const query = `?sessionId=${encodeURIComponent(sessionId)}`
  const url = `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/api/terminal${query}`
  const socket = new WebSocket(url)
  socket.addEventListener('message', (event) => onData(String(event.data)))

  // A size measured before the handshake finished is still the size the terminal
  // has; it goes out as soon as there is a socket to take it (spec-00001-AC-12.5).
  let pending: { cols: number; rows: number } | undefined
  const resize = (cols: number, rows: number) => {
    if (socket.readyState !== WebSocket.OPEN) {
      pending = { cols, rows }
      return
    }
    socket.send(new TextEncoder().encode(JSON.stringify({ cols, rows })))
  }
  socket.addEventListener('open', () => {
    const held = pending
    pending = undefined
    if (held) resize(held.cols, held.rows)
  })

  return {
    send: (data) => socket.readyState === WebSocket.OPEN && socket.send(data),
    resize,
    close: () => socket.close(),
  }
}

export interface TerminalLink {
  send: (data: string) => void
  close: () => void
}

/**
 * The session's terminal channel. The socket carries the replayed buffer as its
 * first frame, so a reconnecting board picks up where it left off (spec-00001-FR-21).
 */
export function connectTerminal(onData: (data: string) => void): TerminalLink {
  const url = `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/api/terminal`
  const socket = new WebSocket(url)
  socket.addEventListener('message', (event) => onData(String(event.data)))
  return {
    send: (data) => socket.readyState === WebSocket.OPEN && socket.send(data),
    close: () => socket.close(),
  }
}

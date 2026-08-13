import { FitAddon } from '@xterm/addon-fit'
import { Terminal as Xterm } from '@xterm/xterm'
import '@xterm/xterm/css/xterm.css'
import { useEffect, useRef } from 'react'
import { connectTerminal } from './terminalSocket.ts'

/** The embedded terminal: streams session output and forwards keystrokes (spec-00001-FR-12). */
export function Terminal({ onClose }: { onClose: () => void }) {
  const host = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!host.current) return
    const xterm = new Xterm({ convertEol: true, fontSize: 12 })
    const fit = new FitAddon()
    xterm.loadAddon(fit)
    xterm.open(host.current)
    fit.fit()

    const link = connectTerminal((data) => xterm.write(data))
    xterm.onData((data) => link.send(data))
    return () => {
      link.close()
      xterm.dispose()
    }
  }, [])

  return (
    <section className="panel" aria-label="Agent session">
      <header className="panel__head">
        <strong>Agent session</strong>
        <button type="button" onClick={onClose}>
          Close
        </button>
      </header>
      <div className="panel__body panel__body--terminal" ref={host} data-testid="terminal-host" />
    </section>
  )
}

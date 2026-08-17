import { FitAddon } from '@xterm/addon-fit'
import { Terminal as Xterm } from '@xterm/xterm'
import '@xterm/xterm/css/xterm.css'
import { Square, TerminalIcon, X } from 'lucide-react'
import { useEffect, useRef } from 'react'
import type { SessionInfo } from './api.ts'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { connectTerminal } from './terminalSocket.ts'

export interface TerminalProps {
  onClose: () => void
  /** End the running session (spec-00001-FR-49). */
  onStop: () => void
  session?: SessionInfo | null
  dark?: boolean
}

const STATUS_VARIANT: Record<string, 'default' | 'secondary' | 'destructive'> = {
  running: 'default',
  exited: 'secondary',
  failed: 'destructive',
}

/**
 * The embedded terminal: streams session output, forwards keystrokes, and reports
 * its own size to the session (spec-00001-FR-12).
 *
 * The size is not decoration. A full-screen TUI draws by the size its pty
 * reports, so a terminal that fits itself and keeps the result to itself leaves
 * the session drawing into a shape nobody is watching (issue-00009). The output
 * arrives through a pty, whose line discipline already turns a newline into a
 * carriage return and a line feed — so no end-of-line rewriting happens here
 * either: doing it twice would return the cursor to column one on every line
 * feed a TUI emits mid-row.
 */
export function Terminal({ onClose, onStop, session, dark }: TerminalProps) {
  const host = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!host.current) return
    const xterm = new Xterm({
      fontSize: 12,
      theme: dark
        ? { background: '#09090b', foreground: '#fafafa' }
        : { background: '#ffffff', foreground: '#18181b', cursor: '#18181b' },
    })
    const fit = new FitAddon()
    xterm.loadAddon(fit)
    xterm.open(host.current)

    const link = connectTerminal((data) => xterm.write(data))
    xterm.onData((data) => link.send(data))

    /**
     * Fit to the panel as it now stands, and tell the session what came of it.
     * A panel collapsed to nothing proposes no columns and no rows, and that is
     * not a size to draw at: it is never sent, so the session keeps the last real
     * one (spec-00001-AC-12.7). A pty refuses a zero size outright, so this is
     * the difference between a folded panel and a broken session.
     */
    const report = () => {
      const proposed = fit.proposeDimensions()
      if (!proposed || proposed.cols <= 0 || proposed.rows <= 0) return
      fit.fit()
      link.resize(xterm.cols, xterm.rows)
    }
    report()
    // The panel is a resizable one, so its size is not settled at mount and does
    // not stay settled: every drag of the divider is a new size to report
    // (spec-00001-AC-12.6).
    const observer = new ResizeObserver(report)
    observer.observe(host.current)

    return () => {
      observer.disconnect()
      link.close()
      xterm.dispose()
    }
  }, [dark])

  return (
    <section aria-label="Agent session" className="flex h-full min-h-0 flex-col">
      <header className="flex items-center gap-2 border-b px-3 py-2">
        <TerminalIcon className="size-4" aria-hidden />
        <span className="text-xs font-medium">Agent session</span>
        {session ? (
          <Badge variant={STATUS_VARIANT[session.status] ?? 'secondary'} className="text-[10px]">
            {session.status}
          </Badge>
        ) : null}
        <div className="ml-auto flex items-center gap-2">
          {/*
            The way out of a session that will not end by itself (spec-00001-FR-49,
            issue-00010). It is offered only while there is a process to end, and
            it is destructive — closing the panel leaves the session running
            (FR-21), this ends it.
          */}
          {session?.status === 'running' ? (
            <Button variant="destructive" size="sm" aria-label="Stop the agent session" onClick={onStop}>
              <Square className="size-4" aria-hidden />
              Stop
            </Button>
          ) : null}
          <Button variant="ghost" size="icon" aria-label="Close" onClick={onClose}>
            <X className="size-4" aria-hidden />
          </Button>
        </div>
      </header>
      <div className="min-h-0 flex-1 p-2" ref={host} data-testid="terminal-host" />
    </section>
  )
}

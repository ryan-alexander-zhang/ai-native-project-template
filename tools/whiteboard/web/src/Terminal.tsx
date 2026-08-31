import { FitAddon } from '@xterm/addon-fit'
import { type ITheme, Terminal as Xterm } from '@xterm/xterm'
import '@xterm/xterm/css/xterm.css'
import { Square, TerminalIcon, X } from 'lucide-react'
import { useEffect, useRef } from 'react'
import type { SessionListing } from './api.ts'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { type TerminalLink, connectTerminal } from './terminalSocket.ts'

export interface TerminalProps {
  onClose: () => void
  /** End the session on show, and no other (spec-00001-FR-49, spec-00003-FR-5). */
  onStop: () => void
  /** The session the panel is showing — one at a time, switched from the session panel. */
  session?: SessionListing | null
  dark?: boolean
  /**
   * How many terminals are kept alive at once. The cap on running sessions is
   * the cap on them (design-00002 §12), so the board hands its own limit down.
   */
  keep?: number
}

/** The cap the board runs with unless the config says otherwise (spec-00003-AC-3.5). */
const DEFAULT_KEEP = 3

const STATUS_VARIANT: Record<string, 'default' | 'secondary' | 'destructive'> = {
  running: 'default',
  awaiting: 'default',
  exited: 'secondary',
  failed: 'destructive',
  terminated: 'secondary',
}

/**
 * What the badge says. Awaiting input is derived from the payload — a running
 * session that has gone quiet — and is not a status of its own (design-00002 §3,
 * spec-00003-FR-6).
 */
function stateOf(session: SessionListing): string {
  return session.status === 'running' && session.awaiting === true ? 'awaiting' : session.status
}

function themeOf(dark?: boolean): ITheme {
  return dark
    ? { background: '#09090b', foreground: '#fafafa' }
    : { background: '#ffffff', foreground: '#18181b', cursor: '#18181b' }
}

/**
 * One session's live terminal. The instance is **not** disposed when another
 * session is put on show: its container leaves the DOM and comes back, which is
 * what keeps the whole output *and* the scroll position across a switch —
 * replaying a buffer could restore the first but never the second
 * (spec-00003-AC-5.1, design-00002 §12).
 */
interface Instance {
  xterm: Xterm
  fit: FitAddon
  link: TerminalLink
  host: HTMLDivElement
  /** Whether xterm has been given its container yet; it may only be given one in the DOM. */
  opened: boolean
  /** When it was last put on show, so the one to let go of is the one nobody has looked at (design-00002 §12). */
  used: number
}

let clock = 0

function shut(instance: Instance): void {
  instance.link.close()
  instance.xterm.dispose()
}

/**
 * The terminal of a session, made if it is new here. Memory grows with the
 * number of sessions kept, so the pool is bounded: past the cap the least
 * recently shown one goes, since the sessions worth their megabyte are the ones
 * being watched (design-00002 §12).
 */
function instanceFor(pool: Map<string, Instance>, id: string, keep: number, dark?: boolean): Instance {
  const held = pool.get(id)
  if (held) return held
  const bound = Math.max(1, keep)
  while (pool.size >= bound) {
    const oldest = [...pool.entries()].reduce((a, b) => (a[1].used <= b[1].used ? a : b))
    shut(oldest[1])
    pool.delete(oldest[0])
  }
  const host = document.createElement('div')
  host.className = 'h-full w-full'
  const xterm = new Xterm({ fontSize: 12, theme: themeOf(dark) })
  const fit = new FitAddon()
  xterm.loadAddon(fit)
  const link = connectTerminal(id, (data) => xterm.write(data))
  xterm.onData((data) => link.send(data))
  const made: Instance = { xterm, fit, link, host, opened: false, used: 0 }
  pool.set(id, made)
  return made
}

/**
 * The embedded terminal: streams the session on show, forwards keystrokes, and
 * reports its own size to that session (spec-00001-FR-12).
 *
 * The size is not decoration. A full-screen TUI draws by the size its pty
 * reports, so a terminal that fits itself and keeps the result to itself leaves
 * the session drawing into a shape nobody is watching (issue-00009). Only the
 * mounted instance can be measured at all, so only the session on show ever
 * sends a frame — and it is sent again the moment that session comes back on
 * show (spec-00003-AC-5.7). The output arrives through a pty, whose line
 * discipline already turns a newline into a carriage return and a line feed — so
 * no end-of-line rewriting happens here either: doing it twice would return the
 * cursor to column one on every line feed a TUI emits mid-row.
 */
export function Terminal({ onClose, onStop, session, dark, keep = DEFAULT_KEEP }: TerminalProps) {
  const host = useRef<HTMLDivElement>(null)
  // The terminals, one per session, outliving every switch between them.
  const pool = useRef(new Map<string, Instance>())
  // Read when an instance is made rather than depended on, so a theme change
  // does not rebuild anything: the instances are retuned in place below.
  const isDark = useRef(dark)
  isDark.current = dark
  // Which session is on show: its own channel, its own terminal (spec-00003-FR-5).
  const sessionId = session?.id

  useEffect(() => {
    const mount = host.current
    if (!mount || sessionId === undefined) return
    const instance = instanceFor(pool.current, sessionId, keep, isDark.current)
    mount.append(instance.host)
    if (!instance.opened) {
      instance.xterm.open(instance.host)
      instance.opened = true
    }
    instance.used = ++clock

    /**
     * Fit to the panel as it now stands, and tell the session what came of it.
     * A panel collapsed to nothing proposes no columns and no rows, and that is
     * not a size to draw at: it is never sent, so the session keeps the last real
     * one (spec-00001-AC-12.7). A pty refuses a zero size outright, so this is
     * the difference between a folded panel and a broken session.
     */
    const report = () => {
      const proposed = instance.fit.proposeDimensions()
      if (!proposed || proposed.cols <= 0 || proposed.rows <= 0) return
      instance.fit.fit()
      instance.link.resize(instance.xterm.cols, instance.xterm.rows)
    }
    report()
    // The panel is a resizable one, so its size is not settled at mount and does
    // not stay settled: every drag of the divider is a new size to report
    // (spec-00001-AC-12.6).
    const observer = new ResizeObserver(report)
    observer.observe(mount)

    return () => {
      observer.disconnect()
      // Out of the DOM, not disposed: switching away must cost the session
      // neither its output nor where the user had scrolled to (spec-00003-AC-5.1).
      instance.host.remove()
    }
  }, [sessionId, keep])

  // A theme change is not a new terminal: retuning the live ones keeps every
  // session's output and scroll exactly where they were (design-00002 §5).
  useEffect(() => {
    for (const instance of pool.current.values()) instance.xterm.options.theme = themeOf(dark)
  }, [dark])

  // The panel itself going away is the end of the terminals: nothing holds them
  // after this, so a socket left open would be a socket nobody can close.
  useEffect(() => {
    const held = pool.current
    return () => {
      for (const instance of held.values()) shut(instance)
      held.clear()
    }
  }, [])

  return (
    <section aria-label="Agent session" className="flex h-full min-h-0 flex-col">
      <header className="flex items-center gap-2 border-b px-3 py-2">
        <TerminalIcon className="size-4" aria-hidden />
        {/* Which session this is: several may be running, and the panel shows
            one of them (spec-00003-FR-5, design-00002 §3). */}
        {session ? (
          <>
            <span className="text-xs font-medium">{session.kind}</span>
            <span className="text-muted-foreground font-mono text-xs">{session.sourceId}</span>
            <Badge variant={STATUS_VARIANT[stateOf(session)] ?? 'secondary'} className="text-[10px]">
              {stateOf(session)}
            </Badge>
          </>
        ) : (
          <span className="text-xs font-medium">Agent session</span>
        )}
        <div className="ml-auto flex items-center gap-2">
          {/*
            The way out of a session that will not end by itself (spec-00001-FR-49,
            issue-00010). It acts on the session on show and is offered only while
            that one has a process to end — an ended session on show offers none,
            whatever else is running (spec-00001-AC-49.7, spec-00003-AC-5.5). It
            is destructive: closing the panel leaves every session running
            (FR-21), this ends one.
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

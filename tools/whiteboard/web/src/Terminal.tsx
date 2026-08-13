import { FitAddon } from '@xterm/addon-fit'
import { Terminal as Xterm } from '@xterm/xterm'
import '@xterm/xterm/css/xterm.css'
import { TerminalIcon, X } from 'lucide-react'
import { useEffect, useRef } from 'react'
import type { SessionInfo } from './api.ts'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { connectTerminal } from './terminalSocket.ts'

export interface TerminalProps {
  onClose: () => void
  session?: SessionInfo | null
  dark?: boolean
}

const STATUS_VARIANT: Record<string, 'default' | 'secondary' | 'destructive'> = {
  running: 'default',
  exited: 'secondary',
  failed: 'destructive',
}

/** The embedded terminal: streams session output and forwards keystrokes (spec-00001-FR-12). */
export function Terminal({ onClose, session, dark }: TerminalProps) {
  const host = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!host.current) return
    const xterm = new Xterm({
      convertEol: true,
      fontSize: 12,
      theme: dark
        ? { background: '#09090b', foreground: '#fafafa' }
        : { background: '#ffffff', foreground: '#18181b', cursor: '#18181b' },
    })
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
        <Button variant="ghost" size="icon" className="ml-auto" aria-label="Close" onClick={onClose}>
          <X className="size-4" aria-hidden />
        </Button>
      </header>
      <div className="min-h-0 flex-1 p-2" ref={host} data-testid="terminal-host" />
    </section>
  )
}

import {
  Bot,
  CircleHelp,
  type LucideIcon,
  MessageCircleQuestionMark,
  NotebookPen,
  Plus,
  ShieldCheck,
  Square,
  TerminalIcon,
} from 'lucide-react'
import type { SessionKind } from '../../src/sessionManager.ts'
import type { SessionListing } from './api.ts'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { stamp } from './status.ts'

export interface SessionPanelProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Every session since the server came up, running and ended alike (spec-00003-FR-4). */
  sessions: SessionListing[]
  /**
   * Whether which agent runs a session is worth a column: only a config
   * declaring more than one makes it a fact about the session rather than a
   * constant (spec-00001-FR-55's reading, spec-00003-AC-4.8/AC-4.9).
   */
  showAgent: boolean
  onPick: (session: SessionListing) => void
  /**
   * End a running session from its own row. Offered on every running session
   * rather than on asks alone: an ask has no terminal panel to be stopped from
   * (spec-00005-FR-7), and a rule that holds for one kind only would be a second
   * rule to remember. The terminal panel's own stop stays where it is.
   */
  onStop: (session: SessionListing) => void
}

/** The kind's own icon, the same one its starting point carries (design-00002 §3). */
const KIND_ICONS: Record<SessionKind, LucideIcon> = {
  advance: Plus,
  clarify: MessageCircleQuestionMark,
  ask: CircleHelp,
  audit: ShieldCheck,
  cowrite: NotebookPen,
}

/**
 * What the row says the session is doing. Awaiting input is a reading of a
 * running session, not a fifth status (spec-00003-FR-6); the three ended ones
 * are kept apart, so a normal exit is never read as a failure or a stop
 * (spec-00003-AC-4.1, AC-4.6, AC-4.7).
 */
function stateOf(session: SessionListing): string {
  return session.status === 'running' && session.awaiting === true ? 'awaiting' : session.status
}

/**
 * Running first, ended after, each group oldest first (design-00002 §3): what is
 * still going on is what the panel is opened for, and start order is the only
 * order that does not move a row under the pointer between two refreshes.
 */
function ordered(sessions: SessionListing[]): SessionListing[] {
  const rank = (session: SessionListing) => (session.status === 'running' ? 0 : 1)
  return [...sessions].sort((a, b) => rank(a) - rank(b) || (a.startedAt < b.startedAt ? -1 : 1))
}

/**
 * The session panel of spec-00003-FR-4: every session the server holds, and the
 * way onto any one of them. It takes the command palette's full-screen dialog,
 * like the governance round's three lists — a global, read-and-leave list, not a
 * monitor to work beside (design-00002 §12). The resident count in the top bar
 * is what watching costs nothing; this is opened to switch or to look.
 *
 * Every row is a real button: Tab reaches it and Enter fires it, which §6's
 * obligation for list rows extends to this fourth list of the same shape.
 */
export function SessionPanel({ open, onOpenChange, sessions, showAgent, onPick, onStop }: SessionPanelProps) {
  const rows = ordered(sessions)
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-3xl">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <TerminalIcon className="size-4" aria-hidden />
            Agent sessions
          </DialogTitle>
          <DialogDescription>
            Sessions since the board came up. Pick one to put it on the terminal.
          </DialogDescription>
        </DialogHeader>

        {rows.length === 0 ? (
          <p className="text-muted-foreground text-xs">no sessions since the board came up</p>
        ) : (
          <ul aria-label="Agent sessions" className="max-h-[60vh] overflow-y-auto">
            {rows.map((session) => {
              const Icon = KIND_ICONS[session.kind]
              return (
                <li key={session.id} className="flex items-center gap-2">
                  <button
                    type="button"
                    onClick={() => onPick(session)}
                    className="hover:bg-accent flex min-w-0 flex-1 flex-wrap items-center gap-2 rounded-md px-2 py-1.5 text-left text-xs"
                  >
                    <Icon className="size-3.5 shrink-0" aria-hidden />
                    <Badge variant="secondary" className="text-[10px]">
                      {session.kind}
                    </Badge>
                    <span className="truncate font-mono">{session.sourceId}</span>
                    {showAgent ? (
                      <span className="text-muted-foreground flex items-center gap-1">
                        <Bot className="size-3" aria-hidden />
                        {session.agent}
                      </span>
                    ) : null}
                    <span
                      className={
                        session.status === 'failed' ? 'text-destructive font-mono' : 'font-mono'
                      }
                    >
                      {stateOf(session)}
                    </span>
                    <span className="text-muted-foreground ml-auto">{stamp(session.startedAt)}</span>
                  </button>
                  {/* The same icon and the same variant as the terminal panel's
                      stop: one act, one shape (design-00002 §14). */}
                  {session.status === 'running' ? (
                    <Button
                      variant="destructive"
                      size="sm"
                      aria-label={`Stop the ${session.kind} session of ${session.sourceId}`}
                      onClick={() => onStop(session)}
                    >
                      <Square className="size-4" aria-hidden />
                      Stop
                    </Button>
                  ) : null}
                </li>
              )
            })}
          </ul>
        )}
      </DialogContent>
    </Dialog>
  )
}

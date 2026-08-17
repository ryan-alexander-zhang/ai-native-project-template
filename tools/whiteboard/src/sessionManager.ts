import { join } from 'node:path'
import type { AgentConfig } from './config.ts'
import { type Expectation, taskInstruction } from './advance.ts'
import type { DirtySnapshot } from './gitLayer.ts'

/** Rolling window of session output replayed on reconnect (spec-00001-AC-21.2). */
const BUFFER_LIMIT = 1024 * 1024

export type SessionStatus = 'running' | 'exited' | 'failed'

export interface SessionInfo {
  id: string
  sourceId: string
  targetType: string
  status: SessionStatus
  exitCode?: number
  error?: string
  /** Set once the exit hook has run: what the session produced and whether it was committed. */
  outcome?: SessionOutcome
}

export interface SessionOutcome {
  docId?: string
  problems: string[]
  committed: boolean
  error?: string
}

export interface PtyProcess {
  onData(listener: (data: string) => void): void
  onExit(listener: (event: { exitCode: number }) => void): void
  write(data: string): void
  kill(): void
}

export type SpawnPty = (command: string, args: string[], cwd: string) => PtyProcess

/** A second advance while one is running is refused (spec-00001-FR-18). */
export class SessionBusyError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'SessionBusyError'
  }
}

interface Session {
  info: SessionInfo
  expectation: Expectation
  /** The docs/ dirt this session inherited; the exit hook scopes its commit against it. */
  baseline: DirtySnapshot
  buffer: string
  pty?: PtyProcess
  listeners: Set<(data: string) => void>
  finished: Promise<void>
}

export interface SessionManagerOptions {
  agent: AgentConfig
  repoRoot: string
  spawn: SpawnPty
  /**
   * The docs/ dirt as it stands, read once per session before the agent can
   * write (design-00001 §4). A manager given none scopes nothing — every
   * dirty path counts as the session's, which is what a caller with no git
   * layer behind it means.
   */
  snapshot?: () => DirtySnapshot
  /** Runs when the process exits: commits and validates what the session produced. */
  onExit: (expectation: Expectation) => Promise<SessionOutcome>
}

/**
 * Owns the one running agent session. Its lifetime is tied to the process, not to
 * any browser connection, so closing the page leaves it running (spec-00001-FR-21).
 */
export class SessionManager {
  private readonly options: SessionManagerOptions
  private session?: Session
  private counter = 0

  constructor(options: SessionManagerOptions) {
    this.options = options
  }

  /** spec-00001-FR-11; a spawn failure yields a failed session carrying the error (FR-16). */
  start(expectation: Expectation): SessionInfo {
    if (this.session?.info.status === 'running') {
      throw new SessionBusyError('an agent session is already running; wait for it to finish')
    }
    const { agent, repoRoot } = this.options
    const info: SessionInfo = {
      id: `s${++this.counter}`,
      sourceId: expectation.sourceId,
      targetType: expectation.targetType,
      status: 'running',
    }
    // Before the spawn, never after: from here on anything under docs/ that
    // moves is the session's doing (spec-00001-AC-14.5).
    const baseline = this.options.snapshot?.() ?? new Map<string, string>()
    const session: Session = {
      info,
      expectation,
      baseline,
      buffer: '',
      listeners: new Set(),
      finished: Promise.resolve(),
    }
    this.session = session

    try {
      session.pty = this.options.spawn(agent.command, agent.args, join(repoRoot, agent.cwd ?? '.'))
    } catch (cause) {
      return this.fail(session, (cause as Error).message)
    }
    session.pty.onData((data) => this.publish(session, data))
    session.pty.onExit((event) => this.exit(session, event.exitCode))
    session.pty.write(`${taskInstruction(expectation)}\n`)
    return info
  }

  /** Replay what the session has printed so far and follow it from there. */
  attach(listener: (data: string) => void): { buffer: string; detach: () => void } {
    const session = this.requireSession()
    session.listeners.add(listener)
    return { buffer: session.buffer, detach: () => session.listeners.delete(listener) }
  }

  /** spec-00001-FR-12: keystrokes from the embedded terminal reach the CLI. */
  write(data: string): void {
    this.requireSession().pty?.write(data)
  }

  current(): SessionInfo | null {
    return this.session?.info ?? null
  }

  /** The dirt the current session started from, for the exit hook to scope its commit by. */
  baseline(): DirtySnapshot {
    return this.session?.baseline ?? new Map<string, string>()
  }

  /** Resolves once the exit hook (commit + directed validation) has run. */
  whenFinished(): Promise<void> {
    return this.session?.finished ?? Promise.resolve()
  }

  stop(): void {
    this.session?.pty?.kill()
  }

  private requireSession(): Session {
    if (!this.session) throw new SessionBusyError('there is no agent session')
    return this.session
  }

  private fail(session: Session, message: string): SessionInfo {
    session.info.status = 'failed'
    session.info.error = message
    this.publish(session, `whiteboard: could not start the agent — ${message}\r\n`)
    return session.info
  }

  private publish(session: Session, data: string): void {
    session.buffer = (session.buffer + data).slice(-BUFFER_LIMIT)
    for (const listener of session.listeners) listener(data)
  }

  private exit(session: Session, exitCode: number): void {
    session.info.status = 'exited'
    session.info.exitCode = exitCode
    this.publish(session, `\r\nwhiteboard: session ended with code ${exitCode}\r\n`)
    session.finished = this.options.onExit(session.expectation).then((outcome) => {
      session.info.outcome = outcome
      this.publish(session, `whiteboard: ${describe(outcome)}\r\n`)
    })
  }
}

function describe(outcome: SessionOutcome): string {
  if (!outcome.docId) return 'no new document was produced'
  const committed = outcome.committed ? 'committed' : `not committed (${outcome.error ?? 'no changes'})`
  const problems = outcome.problems.length === 0 ? '' : ` — ${outcome.problems.join('; ')}`
  return `${outcome.docId} ${committed}${problems}`
}

import { join } from 'node:path'
import type { AgentConfig } from './config.ts'
import type { Expectation } from './advance.ts'
import type { DirtySnapshot } from './gitLayer.ts'
import { writeSessionHistory } from './sessionHistory.ts'
import { WorkflowError } from './workflow.ts'

/** Rolling window of session output replayed on reconnect (spec-00001-AC-21.2). */
const BUFFER_LIMIT = 1024 * 1024

/**
 * How long after the CLI's first output the instruction is submitted, and how
 * long again before the submit is repeated (spec-00001-AC-11.2, issue-00011).
 * Long enough for a TUI that has just printed its frame to have its input box
 * listening, short enough that the agent looks like it started by itself.
 */
const SUBMIT_DELAY_MS = 400

/** The submit keypress itself: Enter as the terminal sends it. */
const SUBMIT = '\r'

export type SessionStatus = 'running' | 'exited' | 'failed'

/**
 * The four kinds of agent session, sharing one channel, one terminal and one
 * slot (spec-00001-FR-18): the board advances the flow, clarify has the agent
 * question the owner, ask has the owner question the agent, audit has the agent
 * review a draft it did not write. The kind is what names the session's commit
 * (spec-00001-FR-14).
 */
export type SessionKind = 'advance' | 'clarify' | 'ask' | 'audit'

/** One session's whole input: what kind it is, what it is about, what it is told. */
export interface SessionPlan {
  kind: SessionKind
  /** The document the session was started from — the source of an advance, the subject of the other two. */
  sourceId: string
  /** The first input written to the CLI; each kind builds its own (advance.ts, sessionTasks.ts). */
  instruction: string
  /** An advance alone expects a product to check on exit (spec-00001-FR-17). */
  expectation?: Expectation
}

export interface SessionInfo {
  id: string
  kind: SessionKind
  sourceId: string
  /** The type an advance was asked to produce; the other kinds produce no new document. */
  targetType?: string
  /** Which agent of the flow config is running it (spec-00001-FR-55). */
  agent: string
  status: SessionStatus
  exitCode?: number
  error?: string
  /** Set once the exit hook has run: what the session produced and whether it was committed. */
  outcome?: SessionOutcome
  /** Why the session's history could not be saved, if it could not (spec-00001-AC-54.3). */
  historyError?: string
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
  /** The size the process believes it is drawing into (spec-00001-FR-12). */
  resize(cols: number, rows: number): void
  kill(): void
}

export type SpawnPty = (command: string, args: string[], cwd: string) => PtyProcess

/** A second session of any kind while one is running is refused (spec-00001-FR-18). */
export class SessionBusyError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'SessionBusyError'
  }
}

/** Asked to stop a session when none is running (spec-00001-FR-49). */
export class NoSessionError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'NoSessionError'
  }
}

interface Session {
  info: SessionInfo
  plan: SessionPlan
  /** The docs/ dirt this session inherited; the exit hook scopes its commit against it. */
  baseline: DirtySnapshot
  startedAt: string
  buffer: string
  /**
   * Everything the session has printed, whole. The replay buffer is a window
   * (BUFFER_LIMIT) because a reconnecting terminal only needs the recent past,
   * while the history transcript is the record of the whole session
   * (spec-00001-FR-54) — so the two are kept apart rather than one being the
   * other's truncation.
   */
  transcript: string[]
  pty?: PtyProcess
  listeners: Set<(data: string) => void>
  finished: Promise<void>
  /** Resolves once the process has exited and the exit hook has run; a stop waits on it. */
  ended: Promise<void>
  announceEnd: () => void
  /** The pending submit keypresses; a session that ends first is never typed into. */
  submits: NodeJS.Timeout[]
}

export interface SessionManagerOptions {
  /** Every agent the flow config declares; a session runs the one it names, or the first (spec-00001-FR-55). */
  agents: AgentConfig[]
  repoRoot: string
  spawn: SpawnPty
  /**
   * The docs/ dirt as it stands, read once per session before the agent can
   * write (design-00001 §4). A manager given none scopes nothing — every
   * dirty path counts as the session's, which is what a caller with no git
   * layer behind it means.
   */
  snapshot?: () => DirtySnapshot
  /** Overrides `SUBMIT_DELAY_MS`, so a test need not wait out the real one. */
  submitDelayMs?: number
  /** Runs when the process exits: commits and validates what the session produced. */
  onExit: (plan: SessionPlan) => Promise<SessionOutcome>
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

  /**
   * spec-00001-FR-11, FR-9 and FR-47; a spawn failure yields a failed session
   * carrying the error (FR-16). `agentName` picks one of the configured agents
   * (spec-00001-FR-55); an unknown name starts nothing at all, which is why it
   * is resolved before anything is created.
   */
  start(plan: SessionPlan, agentName?: string): SessionInfo {
    const agent = this.resolveAgent(agentName)
    if (this.session?.info.status === 'running') {
      throw new SessionBusyError('an agent session is already running; wait for it to finish')
    }
    const { repoRoot } = this.options
    const startedAt = new Date().toISOString()
    const info: SessionInfo = {
      id: this.nextId(startedAt),
      kind: plan.kind,
      sourceId: plan.sourceId,
      targetType: plan.expectation?.targetType,
      agent: agent.name,
      status: 'running',
    }
    // Before the spawn, never after: from here on anything under docs/ that
    // moves is the session's doing (spec-00001-AC-14.5).
    const baseline = this.options.snapshot?.() ?? new Map<string, string>()
    let announceEnd!: () => void
    const ended = new Promise<void>((resolve) => {
      announceEnd = resolve
    })
    const session: Session = {
      info,
      plan,
      baseline,
      startedAt,
      buffer: '',
      transcript: [],
      listeners: new Set(),
      finished: Promise.resolve(),
      ended,
      announceEnd,
      submits: [],
    }
    this.session = session

    try {
      session.pty = this.options.spawn(agent.command, agent.args, join(repoRoot, agent.cwd ?? '.'))
    } catch (cause) {
      return this.fail(session, (cause as Error).message)
    }
    session.pty.onData((data) => {
      this.publish(session, data)
      this.armSubmit(session)
    })
    session.pty.onExit((event) => this.exit(session, event.exitCode))
    // The text alone, ending on whatever it ends on: a submit byte in this same
    // burst is swallowed by the terminal's cooked mode or read as the tail of a
    // paste, and either way never sends (issue-00011). Its own newlines stay LF,
    // which is what a newline inside the input box is.
    session.pty.write(plan.instruction)
    return info
  }

  /**
   * Press Enter on the instruction, once the session has printed anything at
   * all — the nearest thing to "the CLI is up and its input box is listening"
   * that a pty offers. Twice, spaced the same way: the first press is the one
   * that should land, the second costs nothing if it did, since Enter on an
   * empty input box does nothing (issue-00011). Armed on the first output only;
   * later output is the session talking, not a new prompt to answer — and a pty
   * may still emit output after the exit, which is nothing to answer either.
   */
  private armSubmit(session: Session): void {
    if (session.submits.length > 0 || session.info.status !== 'running') return
    const delay = this.options.submitDelayMs ?? SUBMIT_DELAY_MS
    for (const press of [1, 2]) {
      session.submits.push(setTimeout(() => session.pty?.write(SUBMIT), press * delay).unref())
    }
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

  /**
   * spec-00001-FR-12: the terminal's own size reaches the process, which is what
   * a full-screen TUI draws by (issue-00009). A size that lands when nothing is
   * running — the window between an exit and a terminal noticing — has nothing
   * to resize, and refusing it would break the reconnect rather than the frame.
   */
  resize(cols: number, rows: number): void {
    if (this.session?.info.status !== 'running') return
    this.session.pty?.resize(cols, rows)
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

  /**
   * spec-00001-FR-49: end the running session on the user's word. The wrap-up is
   * the ordinary exit path — end state, the kind's commit, a refreshed board — and
   * the caller waits for it, so what comes back is the session as it finished
   * rather than as it was asked to stop (issue-00010).
   */
  async terminate(): Promise<SessionInfo> {
    const session = this.session
    if (session?.info.status !== 'running') {
      throw new NoSessionError('there is no running agent session to stop')
    }
    this.stop()
    await session.ended
    return session.info
  }

  /**
   * The agent a session runs (spec-00001-FR-55): the one it names, or the first
   * the flow config declares — which is the behaviour of every board before the
   * eleventh round, so a single-agent config is unaffected.
   */
  private resolveAgent(name?: string): AgentConfig {
    const { agents } = this.options
    if (name === undefined) return agents[0]!
    const agent = agents.find((candidate) => candidate.name === name)
    if (!agent) {
      throw new WorkflowError(`${JSON.stringify(name)} is not an agent in the flow config`)
    }
    return agent
  }

  /**
   * The session's id, and with it the name of its two history files
   * (spec-00001-FR-54) — so it carries the start time as well as the counter:
   * the counter alone restarts with the process, and a second `s1` would write
   * over the first one's history.
   */
  private nextId(startedAt: string): string {
    return `${startedAt.replace(/[:.]/g, '-')}-${++this.counter}`
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
    session.transcript.push(data)
    for (const listener of session.listeners) listener(data)
  }

  /**
   * The session's history on disk (spec-00001-FR-54): the metadata as JSON, the
   * whole transcript as plain text. It runs before the wrap-up's commit
   * (design-00001 §5) and blocks nothing — a directory that cannot be written to
   * costs the user this record and nothing else, so the failure is a notice in
   * the terminal they are already looking at (spec-00001-AC-54.3).
   */
  private saveHistory(session: Session): void {
    try {
      writeSessionHistory(
        this.options.repoRoot,
        {
          id: session.info.id,
          kind: session.plan.kind,
          docId: session.plan.sourceId,
          agent: session.info.agent,
          startedAt: session.startedAt,
          endedAt: new Date().toISOString(),
          status: session.info.status,
          exitCode: session.info.exitCode,
        },
        session.transcript.join(''),
      )
    } catch (cause) {
      const message = (cause as Error).message
      session.info.historyError = message
      this.publish(session, `whiteboard: could not save the session history — ${message}\r\n`)
    }
  }

  private exit(session: Session, exitCode: number): void {
    // Nothing is typed into a session that has ended, and no timer outlives it.
    for (const submit of session.submits.splice(0)) clearTimeout(submit)
    session.info.status = 'exited'
    session.info.exitCode = exitCode
    this.publish(session, `\r\nwhiteboard: session ended with code ${exitCode}\r\n`)
    this.saveHistory(session)
    session.finished = this.options
      .onExit(session.plan)
      .then((outcome) => {
        session.info.outcome = outcome
        this.publish(session, `whiteboard: ${describe(outcome)}\r\n`)
      })
      .finally(session.announceEnd)
  }
}

function describe(outcome: SessionOutcome): string {
  if (!outcome.docId) return 'no new document was produced'
  const committed = outcome.committed ? 'committed' : `not committed (${outcome.error ?? 'no changes'})`
  const problems = outcome.problems.length === 0 ? '' : ` — ${outcome.problems.join('; ')}`
  return `${outcome.docId} ${committed}${problems}`
}

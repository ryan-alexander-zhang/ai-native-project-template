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

/**
 * How long a running session must print nothing before it is read as waiting on
 * the user (spec-00003-FR-6). An implementation constant of the order of ten
 * seconds, not a config key (design-00001 §5): the judgment is deliberately weak
 * — a false positive costs a badge and nothing more, since the mark drives no
 * transition and no commit.
 */
const AWAIT_THRESHOLD_MS = 10_000

/**
 * The explicit «I am waiting for you» a CLI sends of its own accord: the head of
 * an OSC 777 terminal notification, ESC plus twelve characters
 * (spec-00003-FR-6, decision-00011). The prefix alone is the signal — the title
 * and the body are the CLI's wording, which changes with its version and its
 * locale, so they are never read.
 */
const AWAIT_SIGNAL = '\x1b]777;notify;'

/**
 * How a session stands. `terminated` is the third end state of the sixteenth
 * round (design-00001 §5): a session the user stopped ended on its own wrap-up
 * like any other, but the panel and the history have to say it was stopped
 * rather than that it finished (spec-00003-FR-4).
 */
export type SessionStatus = 'running' | 'exited' | 'failed' | 'terminated'

/**
 * The four kinds of agent session, sharing one channel and one registry: the
 * board advances the flow, clarify has the agent question the owner, ask has the
 * owner question the agent, audit has the agent review a draft it did not write.
 * No kind is exclusive of another — the concurrency rules are per target
 * document and per total (spec-00003-FR-1). The kind is what names the session's
 * commit (spec-00001-FR-14).
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

/**
 * One row of `GET /api/sessions` (design-00001 §7): the session as it stands,
 * plus when it ran — the session panel lists every session since boot by its
 * start time (spec-00003-FR-4). The times are the registry's, not the started
 * session's own answer, which is why they live here and not on `SessionInfo`.
 */
export interface SessionListing extends SessionInfo {
  startedAt: string
  endedAt?: string
  /**
   * True while the session is read as «waiting on the user» (spec-00003-FR-6):
   * it has printed nothing for the silence threshold, or it said so itself with
   * an OSC 777 notification, and its process is still alive. Never true
   * of a session that has ended, whichever way it ended; it is the registry's
   * reading of the session rather than anything the session said, which is why it
   * lives here and not on `SessionInfo`.
   */
  awaiting?: boolean
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

/**
 * Why a start was refused, in a word the board can act on (design-00001 §7):
 * the target document already has a running session (spec-00003-FR-2), or the
 * cap is reached (spec-00003-FR-3). The two are told apart because the entry's
 * hover text says which one holds (spec-00001-FR-49).
 */
export type SessionRefusal = 'doc-busy' | 'cap-reached'

/** A start the concurrency rules refuse (spec-00003-FR-2, spec-00003-FR-3). */
export class SessionBusyError extends Error {
  readonly reason: SessionRefusal

  constructor(message: string, reason: SessionRefusal) {
    super(message)
    this.name = 'SessionBusyError'
    this.reason = reason
  }
}

/**
 * Addressed to a session that is not there to be addressed: an id the registry
 * does not know, or one whose session has already ended. Judged per session, so
 * another session running changes nothing about this answer
 * (spec-00001-AC-49.4, spec-00003-AC-5.5).
 */
export class NoSessionError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'NoSessionError'
  }
}

interface Session {
  info: SessionInfo
  plan: SessionPlan
  /** The docs/ dirt this session inherited; its own commit is scoped against it. */
  baseline: DirtySnapshot
  startedAt: string
  endedAt?: string
  /** Set by `terminate` before the signal, so the exit knows it was stopped, not finished. */
  stopping?: boolean
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
  /** Whether the silence has already been read as «waiting on the user» (spec-00003-FR-6). */
  awaiting?: boolean
  /** The pending silence window, re-armed by every output and cleared by the end. */
  silence?: NodeJS.Timeout
  /**
   * Whether the session said outright that it is waiting (spec-00003-FR-6): once
   * latched, only the user's input or the end takes the mark down, and the output
   * that follows is read as the redraw noise it is (decision-00011 §2).
   */
  latched?: boolean
  /**
   * The tail of the last chunk that could still be the start of the signal, so a
   * sequence split across two chunks is recognised (design-00001 §5). Bounded by
   * the signal's own length, which is what keeps it from growing.
   */
  signalTail: string
}

export interface SessionManagerOptions {
  /** Every agent the flow config declares; a session runs the one it names, or the first (spec-00001-FR-55). */
  agents: AgentConfig[]
  /** How many sessions may run at once — `max_sessions` of the flow config (spec-00003-FR-3). */
  maxSessions: number
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
  /** Overrides `AWAIT_THRESHOLD_MS`, so a test need not wait out the real silence. */
  awaitThresholdMs?: number
  /**
   * Called when a session's waiting-on-the-user mark goes up or comes down
   * (spec-00003-FR-6). A board learns session state by re-reading after the
   * refresh signal (spec-00001-FR-42) and by nothing else, so a flip nobody
   * announced would never reach the panel; the caller routes it through the
   * signal's own debounce window.
   */
  onAwaitingChange?: () => void
  /**
   * Runs when a process exits: commits and validates what that session produced.
   * It is handed the session's **own** baseline, because several sessions run at
   * once and each commits the difference from the dirt it alone inherited
   * (spec-00003-FR-8, design-00001 §4).
   */
  onExit: (plan: SessionPlan, baseline: DirtySnapshot) => Promise<SessionOutcome>
}

/**
 * The registry of agent sessions (design-00001 §5): every session started since
 * the server came up, running or ended, keyed by its id. Several run at once,
 * bounded by the two concurrency rules — one running session per target document
 * and `max_sessions` in total (spec-00003-FR-1 … FR-3). A session's lifetime is
 * tied to its process, not to any browser connection, so closing the page leaves
 * them all running (spec-00001-FR-21, spec-00003-FR-9).
 */
export class SessionManager {
  private readonly options: SessionManagerOptions
  /** Insertion order is start order, which is what «the newest session» reads off. */
  private readonly sessions = new Map<string, Session>()
  private counter = 0
  /** The one shutdown, once it has begun: a second call joins it (spec-00003-AC-9.3). */
  private shuttingDown?: Promise<void>

  constructor(options: SessionManagerOptions) {
    this.options = options
  }

  /**
   * spec-00001-FR-11, FR-9 and FR-47; a spawn failure yields a failed session
   * carrying the error (FR-16). `agentName` picks one of the configured agents
   * (spec-00001-FR-55); an unknown name starts nothing at all, which is why it
   * is resolved before anything is created.
   *
   * Admission and taking the slot happen in this one synchronous call
   * (design-00001 §5): two starts racing for the last slot are therefore ordered
   * by arrival, and first come first served needs no lock of its own
   * (spec-00003-AC-3.6).
   */
  start(plan: SessionPlan, agentName?: string): SessionInfo {
    const agent = this.resolveAgent(agentName)
    this.admit(plan)
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
      signalTail: '',
    }
    this.sessions.set(info.id, session)

    try {
      session.pty = this.options.spawn(agent.command, agent.args, join(repoRoot, agent.cwd ?? '.'))
    } catch (cause) {
      return this.fail(session, (cause as Error).message)
    }
    session.pty.onData((data) => {
      this.publish(session, data)
      this.armSubmit(session)
      // Recognition before release, in this order and not the other
      // (spec-00003-FR-6): a chunk carrying the signal only sets and latches,
      // and the bytes around the sequence in that same chunk clear nothing.
      this.recognizeSignal(session, data)
      this.armSilence(session)
    })
    session.pty.onExit((event) => this.exit(session, event.exitCode))
    // The clock starts at the spawn, not at the first output: a CLI that prints
    // nothing at all is as silent as one that stopped printing (spec-00003-FR-6).
    this.armSilence(session)
    // The text alone, ending on whatever it ends on: a submit byte in this same
    // burst is swallowed by the terminal's cooked mode or read as the tail of a
    // paste, and either way never sends (issue-00011). Its own newlines stay LF,
    // which is what a newline inside the input box is.
    session.pty.write(plan.instruction)
    return info
  }

  /**
   * The two concurrency rules, judged together and in this order
   * (spec-00003-FR-2, FR-3): the target document first, the total second, so a
   * start that breaks both is refused with the more specific reason — the same
   * order the disabled entry's hover text follows (spec-00001-FR-49).
   *
   * The target document of an advance is its **source** (spec-00001-FR-19's
   * reading, spec-00003-AC-2.6), which is what `sourceId` already holds for
   * every kind. A failed session is out of both counts: it holds no slot
   * (spec-00003-AC-3.7) and it is running nothing to be exclusive of.
   */
  private admit(plan: SessionPlan): void {
    const running = this.running()
    if (running.some((session) => session.info.sourceId === plan.sourceId)) {
      throw new SessionBusyError(`${plan.sourceId} already has a running agent session`, 'doc-busy')
    }
    if (running.length >= this.options.maxSessions) {
      throw new SessionBusyError(
        `${running.length} agent sessions are already running, which is the max_sessions limit`,
        'cap-reached',
      )
    }
  }

  private running(): Session[] {
    return [...this.sessions.values()].filter((session) => session.info.status === 'running')
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

  /**
   * Restart the silence window (spec-00003-FR-6). Output is proof the session is
   * not waiting on anybody — unless it is latched, see below — so this both takes
   * the mark down and arms the next window; a session whose process is gone is
   * never armed again, which is the whole of «a process that has exited does not
   * enter the judgment» — neither its last words nor the wrap-up's silence can
   * mark it (spec-00003-AC-6.4).
   */
  private armSilence(session: Session): void {
    clearTimeout(session.silence)
    session.silence = undefined
    // Latched, the mark stays up whatever arrives: after the session has said it
    // is waiting, only the user's input or its end means otherwise. The window
    // below is armed all the same, and its firing is then the no-op it looks
    // like — the mark is already up (design-00001 §5).
    if (!session.latched) this.setAwaiting(session, false)
    if (session.info.status !== 'running') return
    const threshold = this.options.awaitThresholdMs ?? AWAIT_THRESHOLD_MS
    // Unreffed like the submits: a board is not held open by a session's silence.
    session.silence = setTimeout(() => this.setAwaiting(session, true), threshold).unref()
  }

  /**
   * The mark, and the refresh that carries it. Only a real change is announced,
   * so the ordinary case — output arriving on a session that was never marked —
   * signals nothing (spec-00003-FR-6).
   */
  private setAwaiting(session: Session, awaiting: boolean): void {
    if ((session.awaiting ?? false) === awaiting) return
    session.awaiting = awaiting
    this.options.onAwaitingChange?.()
  }

  /**
   * The signal path (spec-00003-FR-6, decision-00011): the session saying so
   * itself, which is worth more than the silence it broke by saying it. Set at
   * once — no threshold to wait out — and latched, so the idle redraws that
   * follow cannot flap the mark. A repeat while latched changes nothing, and a
   * process that has exited is never marked, its last words included
   * (spec-00003-AC-6.10).
   */
  private recognizeSignal(session: Session, data: string): void {
    if (session.info.status !== 'running') return
    const scanned = session.signalTail + data
    if (scanned.includes(AWAIT_SIGNAL)) {
      session.latched = true
      this.setAwaiting(session, true)
    }
    session.signalTail = carriedTail(scanned)
  }

  /** Back to the silence path: the latch gone, output speaks for the session again. */
  private unlatch(session: Session): void {
    if (!session.latched) return
    session.latched = false
    this.armSilence(session)
  }

  /**
   * Replay what that session has printed so far and follow it from there. Each
   * session keeps its own buffer and its own listeners, so output reaches the
   * terminal watching it and no other (spec-00003-AC-1.2).
   */
  attach(id: string, listener: (data: string) => void): { buffer: string; detach: () => void } {
    const session = this.require(id)
    session.listeners.add(listener)
    return { buffer: session.buffer, detach: () => session.listeners.delete(listener) }
  }

  /**
   * spec-00001-FR-12: keystrokes from a terminal reach the CLI of the session it
   * is attached to. This is also the one write the latch listens for — any
   * keypress at all, no submit needed (spec-00003-FR-6, decision-00011 §2):
   * someone who starts typing and stops is caught again by the silence path.
   * The server's own writes — the instruction body and the submit keypresses —
   * do not come through here, and so do not unlatch.
   */
  write(id: string, data: string): void {
    const session = this.require(id)
    this.unlatch(session)
    session.pty?.write(data)
  }

  /**
   * spec-00001-FR-12: the terminal's own size reaches the process, which is what
   * a full-screen TUI draws by (issue-00009). A size that lands on a session that
   * is not running — the window between an exit and a terminal noticing, or a
   * session nobody is presenting any more — has nothing to resize, and refusing
   * it would break the reconnect rather than the frame (spec-00003-FR-5).
   */
  resize(id: string, cols: number, rows: number): void {
    const session = this.sessions.get(id)
    if (session?.info.status !== 'running') return
    session.pty?.resize(cols, rows)
  }

  /**
   * Every session since the server came up, oldest first: the running ones and
   * the ended ones alike (spec-00003-FR-4). Nothing is persisted — a restart
   * starts this list empty, and the whole history lives on disk instead
   * (spec-00001-FR-54, design-00001 §5).
   */
  list(): SessionListing[] {
    return [...this.sessions.values()].map((session) => ({
      ...session.info,
      startedAt: session.startedAt,
      endedAt: session.endedAt,
      awaiting: session.awaiting,
    }))
  }

  /**
   * The most recently started session, which is the one a terminal that was not
   * told which session to show falls back to (spec-00003-FR-9). Choosing among
   * several is the session panel's business (spec-00003-FR-4, FR-5).
   */
  latest(): SessionInfo | null {
    return [...this.sessions.values()].at(-1)?.info ?? null
  }

  /**
   * The numbers already handed to advance sessions that are still running
   * (spec-00003-FR-1): allocation counts them as taken, so two parallel advances
   * of the same target type cannot be given the same number even though neither
   * document is on disk yet (spec-00003-AC-1.3). Derived from the running
   * sessions rather than kept in a set of its own — that is what releases the
   * number the moment a session ends, whichever way it ended (design-00001 §5).
   */
  reservedNumbers(type: string): number[] {
    return this.running()
      .map((session) => session.plan.expectation)
      .filter((expectation) => expectation?.targetType === type)
      .map((expectation) => expectation!.number)
  }

  /** Resolves once that session's exit hook (commit + directed validation) has run. */
  whenFinished(id?: string): Promise<void> {
    const session = id === undefined ? [...this.sessions.values()].at(-1) : this.sessions.get(id)
    return session?.finished ?? Promise.resolve()
  }

  /** Signal every running session's process, without waiting on any of them. */
  stop(): void {
    for (const session of this.running()) session.pty?.kill()
  }

  /**
   * spec-00001-FR-49: end one session on the user's word. The wrap-up is the
   * ordinary exit path — end state, the kind's commit, a refreshed board — and
   * the caller waits for it, so what comes back is the session as it finished
   * rather than as it was asked to stop (issue-00010). Judged per session: an id
   * the registry never knew, or one whose session has already ended, is refused
   * however many other sessions are running (spec-00001-AC-49.4,
   * spec-00003-AC-5.5).
   */
  async terminate(id: string): Promise<SessionInfo> {
    const session = this.sessions.get(id)
    if (session?.info.status !== 'running') {
      throw new NoSessionError(`there is no running agent session ${id} to stop`)
    }
    // Read by the exit hook, which is why it is set before the signal: the same
    // wrap-up runs, and only the end state says the user stopped it.
    session.stopping = true
    session.pty?.kill()
    await session.ended
    return session.info
  }

  /**
   * The server is going down normally, so every running session gets the wrap-up
   * a stop would have given it — process ended, history written, commit through
   * the one serial queue — and only then may the process go (spec-00003-FR-9,
   * design-00001 §5). Bounded by the same signal escalation a stop is bounded by
   * (issue-00012), which is what makes waiting for all of them safe.
   *
   * Settled rather than all: one session whose wrap-up throws must not leave the
   * others' commits unwaited for. Each is awaited to its exit hook and not
   * merely to its exit, because the commit is the whole point of waiting.
   *
   * Nothing here is persisted, and nothing is meant to be: the registry is
   * memory, so the next boot lists no sessions and the transcripts are looked up
   * in the session history instead (spec-00003-AC-9.3, spec-00001-FR-54). A
   * crash promises none of this.
   */
  shutdown(): Promise<void> {
    // Memoised, so a second signal arriving mid-shutdown joins the one running
    // instead of wrapping anything up twice, and a call after it has finished is
    // the no-op it should be.
    this.shuttingDown ??= Promise.allSettled(
      this.running().map(async (session) => {
        const { id } = session.info
        await this.terminate(id)
        await this.whenFinished(id)
      }),
    ).then(() => {})
    return this.shuttingDown
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

  private require(id: string): Session {
    const session = this.sessions.get(id)
    if (!session) throw new NoSessionError(`there is no agent session ${id}`)
    return session
  }

  /**
   * spec-00001-FR-16: the agent never started. The slot goes back at once — a
   * failed session runs nothing, so it counts towards no cap (spec-00003-AC-3.7)
   * — while the session itself stays in the registry, because the panel lists it
   * as «failed» (spec-00003-AC-4.6). Both of those follow from the status alone,
   * which is the whole of the bookkeeping.
   */
  private fail(session: Session, message: string): SessionInfo {
    session.info.status = 'failed'
    session.endedAt = new Date().toISOString()
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
  private saveHistory(session: Session, endedAt: string): void {
    try {
      writeSessionHistory(
        this.options.repoRoot,
        {
          id: session.info.id,
          kind: session.plan.kind,
          docId: session.plan.sourceId,
          agent: session.info.agent,
          startedAt: session.startedAt,
          endedAt,
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
    // A session the user stopped ends `terminated`, one that ran out ends
    // `exited` (design-00001 §5). Whichever it is, the wrap-up below is the same
    // one and runs exactly once — `onExit` fires once, so a stop that races a
    // natural exit is settled by whichever got here first (spec-00001-FR-49).
    session.info.status = session.stopping ? 'terminated' : 'exited'
    session.info.exitCode = exitCode
    // An ended session is not waiting on anybody, whatever it was a moment ago,
    // and its silence from here on is the wrap-up's (spec-00003-AC-6.3, AC-6.4).
    // Cleared without announcing it: the end is a refresh trigger in its own
    // right, and one refresh carries both (spec-00001-AC-12.8).
    clearTimeout(session.silence)
    session.silence = undefined
    session.awaiting = undefined
    session.latched = false
    const endedAt = new Date().toISOString()
    session.endedAt = endedAt
    this.publish(session, `\r\nwhiteboard: session ended with code ${exitCode}\r\n`)
    // The history records the end state as it is, `terminated` included
    // (design-00001 §7): a restart must not turn a stopped session into one that
    // finished (spec-00001-AC-54.4).
    this.saveHistory(session, endedAt)
    session.finished = this.options
      .onExit(session.plan, session.baseline)
      .then((outcome) => {
        session.info.outcome = outcome
        this.publish(session, `whiteboard: ${describe(outcome)}\r\n`)
      })
      .finally(session.announceEnd)
  }
}

/**
 * What of the scanned text has to be carried into the next chunk: the longest
 * proper prefix of the signal that ends the text (design-00001 §5). Bounded by
 * one less than the signal's length, so the buffer cannot grow with the output.
 */
function carriedTail(scanned: string): string {
  for (let length = Math.min(AWAIT_SIGNAL.length - 1, scanned.length); length > 0; length -= 1) {
    const tail = scanned.slice(-length)
    if (AWAIT_SIGNAL.startsWith(tail)) return tail
  }
  return ''
}

function describe(outcome: SessionOutcome): string {
  if (!outcome.docId) return 'no new document was produced'
  const committed = outcome.committed ? 'committed' : `not committed (${outcome.error ?? 'no changes'})`
  const problems = outcome.problems.length === 0 ? '' : ` — ${outcome.problems.join('; ')}`
  return `${outcome.docId} ${committed}${problems}`
}

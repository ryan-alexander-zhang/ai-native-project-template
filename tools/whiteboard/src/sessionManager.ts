import { join } from 'node:path'
import type { AgentConfig } from './config.ts'
import type { Expectation } from './advance.ts'
import type { AskResult } from './askStore.ts'
import { HAND_EDIT_NOTE } from './cowrite.ts'
import type { ContentSnapshot, DirtySnapshot } from './gitLayer.ts'
import { type SpawnHeadless, failureReason, headlessArgs, readCapture, spawnHeadless } from './headless.ts'
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
 * The five kinds of agent session, sharing one registry: the board advances the
 * flow, clarify has the agent question the owner, ask has the owner question the
 * agent, audit has the agent review a draft it did not write, and cowrite has the
 * two of them write one document together (spec-00006-FR-1). All of them but ask
 * are terminal sessions on a pty; ask is the registry's second form — a captured
 * headless call with no terminal at all (spec-00005-FR-6, design-00001 §10.3).
 * The kind is what names a terminal session's commit (spec-00001-FR-14); an ask
 * makes none.
 */
export type SessionKind = 'advance' | 'clarify' | 'ask' | 'audit' | 'cowrite'

/** One session's whole input: what kind it is, what it is about, what it is told. */
export interface SessionPlan {
  kind: SessionKind
  /** The document the session was started from — the source of an advance, the subject of the other three. */
  sourceId: string
  /**
   * What the CLI is told: the first input written to a terminal session's pty,
   * and the argv payload of an ask call (design-00001 §10.1). Each kind builds
   * its own (advance.ts, sessionTasks.ts).
   */
  instruction: string
  /** An advance alone expects a product to check on exit (spec-00001-FR-17). */
  expectation?: Expectation
  /** An ask alone: which thread of the document's ask list this call belongs to (spec-00005-FR-2). */
  threadId?: string
  /** An ask follow-up alone: the CLI's resume id, which is what makes the call a resume rather than a first. */
  resumeId?: string
  /**
   * A cowrite alone: the target as it stood when the session was admitted
   * (design-00001 §11.3, §11.4). The path is what the collapse filter addresses
   * the target by, and the two front matter values are what its guard puts back
   * — read at plan time, because by the time the session ends the file on disk is
   * whatever the agent left (spec-00006-AC-6.4).
   */
  cowrite?: { targetPath: string; preId: string; preStatus: string }
  /**
   * A cowrite alone: the whole text of every path that was already dirty when it
   * started (design-00001 §11.3). Filled in by the manager rather than by the
   * caller — the reading has to happen after admission and before the spawn, like
   * the digest baseline beside it — and it is what the filter restores a path
   * from that this session should never have touched (spec-00006-FR-6).
   */
  contentBaseline?: ContentSnapshot
}

/**
 * What one still-running session may write, as the registry can state it
 * (design-00001 §11.3). Paths are not in here: resolving a document id to the
 * file it lives in is the doc service's reading, and the registry holds no graph.
 */
export interface SessionClaim {
  kind: SessionKind
  /** The document the session is about; its file is claimed whichever kind this is. */
  sourceId: string
  /** A cowrite alone: the target it writes, relative to the docs tree. */
  targetPath?: string
  /** An advance alone: the type folder its product is filed in, and the id prefix it must carry. */
  targetType?: string
  idPrefix?: string
  /**
   * The docs/ dirt that session inherited — what tells a reference **another**
   * cowrite created from one that was already there when it started.
   */
  baseline: DirtySnapshot
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
  /**
   * Why the session's record could not be saved, if it could not
   * (spec-00001-AC-54.3): its history, or — for an ask — the answer that was to
   * be landed on its thread. Either failure blocks nothing; it is a notice.
   */
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
  /** The agent entry the session runs; an ask reads its headless declaration at launch. */
  agent: AgentConfig
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
  /** What an ask call printed, kept apart so the capture reads stdout alone (design-00001 §10.3). */
  stdout: string
  stderr: string
  /** How the running process is signalled, whichever seam started it. */
  kill?: () => void
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
  /**
   * A cowrite whose target the user has edited by hand and saved, with the note
   * not yet handed over (spec-00006-FR-5): it rides ahead of the next printable
   * frame the user sends, and until one arrives it waits. A session that ends
   * first takes the note with it — the hand edit is already committed by the
   * write path, so nothing is lost (design-00001 §11.4).
   */
  pendingNote?: boolean
  /**
   * Whether the CLI's input line holds printable characters the user has not
   * submitted yet, tracked from their own frames alone (design-00001 §11.4). The
   * note may only go in on an **empty** line: spliced into a half-typed one it
   * would land inside the word being typed, which is the issue-00011 defect over
   * again (spec-00006-AC-5.3).
   */
  lineFilled?: boolean
}

export interface SessionManagerOptions {
  /** Every agent the flow config declares; a session runs the one it names, or the first (spec-00001-FR-55). */
  agents: AgentConfig[]
  /** How many sessions may run at once — `max_sessions` of the flow config (spec-00003-FR-3). */
  maxSessions: number
  repoRoot: string
  spawn: SpawnPty
  /**
   * The second spawn seam (design-00001 §10.1): how an ask call's child process
   * is started. Beside the pty one and never through it — an ask has no
   * terminal, and its kill ladder is its own.
   */
  spawnHeadless?: SpawnHeadless
  /**
   * The docs/ dirt as it stands, read once per session before the agent can
   * write (design-00001 §4). A manager given none scopes nothing — every
   * dirty path counts as the session's, which is what a caller with no git
   * layer behind it means.
   */
  snapshot?: () => DirtySnapshot
  /**
   * The whole text of the docs/ dirt as it stands, read once before a **cowrite**
   * session can write (design-00001 §11.3): the baseline its collapse filter
   * restores from. A manager given none restores nothing from the snapshot and
   * falls back to HEAD, which is what a caller with no git layer behind it means.
   */
  contentSnapshot?: () => ContentSnapshot
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
   * Runs when an ask call ends, before its history is written
   * (design-00001 §10.3): what the call yielded, for the thread to be landed on.
   */
  onAskEnd?: (plan: SessionPlan, result: AskResult) => Promise<void>
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
    const info = this.startDeferred(plan, agentName)
    // The slot is taken; the process waits. An ask call's record has to be on
    // disk before there is a process to reconcile it against, so its spawn is
    // the caller's next step rather than this one's (design-00001 §10.2 写序).
    if (plan.kind === 'ask') return info
    return this.launchTerminal(info.id)
  }

  /**
   * Admission alone: the agent resolved, the concurrency rules judged, the slot
   * taken and both baselines read — and no process (design-00001 §10.2 写序,
   * §11.2 create 形的受理次序). Two callers hold a slot across a write of their
   * own before the spawn: an ask, whose record has to be on disk first, and a
   * cowrite that is creating its own target, which must not file a document if
   * the cap refuses it (spec-00006-AC-2.6).
   */
  startDeferred(plan: SessionPlan, agentName?: string): SessionInfo {
    const agent = this.resolveAgent(agentName, plan.kind)
    this.admit(plan)
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
    // moves is the session's doing (spec-00001-AC-14.5). An ask takes none —
    // it commits nothing, so it has nothing to scope a commit against
    // (spec-00005-FR-4).
    const baseline = plan.kind === 'ask' ? new Map<string, string>() : (this.options.snapshot?.() ?? new Map())
    // A cowrite's filter restores what it filters, so the digests above are not
    // enough for it: the whole text of the same dirt is read in the same window,
    // and on the plan, which is what the exit hook is handed (design-00001 §11.3).
    if (plan.kind === 'cowrite') plan.contentBaseline = this.options.contentSnapshot?.() ?? new Map()
    let announceEnd!: () => void
    const ended = new Promise<void>((resolve) => {
      announceEnd = resolve
    })
    const session: Session = {
      info,
      plan,
      agent,
      baseline,
      startedAt,
      buffer: '',
      transcript: [],
      stdout: '',
      stderr: '',
      listeners: new Set(),
      finished: Promise.resolve(),
      ended,
      announceEnd,
      submits: [],
      signalTail: '',
    }
    this.sessions.set(info.id, session)
    return info
  }

  /**
   * The second half of starting a session on a terminal: the pty, its handlers
   * and the first input (spec-00001-FR-11). Split from the admission above so a
   * cowrite that creates its own target can file the document between the two —
   * the slot first, the file second, the process last (design-00001 §11.2), which
   * is what makes the create either whole or nothing (spec-00006-FR-2).
   */
  launchTerminal(id: string): SessionInfo {
    const session = this.requireTerminal(id)
    const { repoRoot } = this.options
    const { agent, info, plan } = session
    try {
      session.pty = this.options.spawn(agent.command, agent.args, join(repoRoot, agent.cwd ?? '.'))
      session.kill = () => session.pty?.kill()
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
    // A stop that landed between the admission and here — the create form holds
    // its slot across a file write, so a terminate or a shutdown can arrive in
    // that window — found no process to signal and left only its mark: it is
    // honoured now, or this pty would run on with nobody to kill it and the
    // shutdown would wait on it for ever. After the handlers and not before, for
    // the reason `launch` gives: an end nobody is listening for never wraps up.
    if (session.stopping) session.kill()
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
   * The second half of starting an ask call (design-00001 §10.2 写序): the
   * process, once the caller has the `running` exchange on disk. Split from
   * `start` because a slot held across that write is what keeps the cap honest —
   * admission and the record cannot both be first, and a record with no process
   * is reconcilable while a process with no record is not
   * (spec-00005-AC-5.3, AC-6.4).
   */
  launch(id: string): SessionInfo {
    const session = this.require(id)
    const { repoRoot } = this.options
    const { agent } = session
    const spawn = this.options.spawnHeadless ?? spawnHeadless
    // The declared flag set whole, and never the entry's `args`: those are the
    // interactive form's (design-00001 §10.1). The instruction goes in as one
    // argv element, so there is no shell and no escaping.
    const args = headlessArgs(agent.headless!, session.plan.instruction, session.plan.resumeId)
    try {
      const child = spawn(agent.command, args, join(repoRoot, agent.cwd ?? '.'))
      session.kill = () => child.kill()
      child.onStdout((chunk) => {
        session.stdout += chunk
      })
      child.onStderr((chunk) => {
        session.stderr += chunk
      })
      child.onExit((event) => this.exit(session, event.exitCode))
      // A stop that landed between the admission and here found no process to
      // signal and left only its mark: it is honoured now, or the call would run
      // on with its session already reading `terminated` (spec-00005-AC-7.6).
      // After the listeners and not before — a kill this signals may end the
      // call at once, and an end nobody is listening for is a session that never
      // wraps up.
      if (session.stopping) session.kill()
    } catch (cause) {
      // A seam that throws leaves a session admitted with no process, no way to
      // signal it and no exit to come: it would hold its slot for good and a
      // shutdown would wait on it for ever. So it takes the ordinary end
      // instead — a call that never ran is a call that failed, and its exchange
      // lands `failed` down the one path every ask call ends on.
      session.stderr += `whiteboard: could not start the agent — ${(cause as Error).message}\n`
      session.info.error = (cause as Error).message
      this.exit(session, 1)
    }
    return session.info
  }

  /**
   * Give up a session that was admitted but never got a process. Its caller
   * takes the slot before writing the record the process is reconciled against
   * (design-00001 §10.2 写序), so a write that fails leaves a session running
   * with nothing behind it — this is how that is undone. It ends `failed`, like
   * a spawn that never started: the slot goes back at once
   * (spec-00003-AC-3.7) and the panel still lists what happened.
   */
  abandon(id: string, reason: string): void {
    const session = this.sessions.get(id)
    if (session?.info.status !== 'running') return
    this.fail(session, reason)
    session.announceEnd()
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
   *
   * An ask takes no document, in both directions (spec-00005-FR-6): it is
   * refused by nothing running on that document, and nothing running on it is
   * refused by an ask. The exclusion is therefore judged over the terminal
   * sessions alone (design-00001 §10.3) — reading it one way only would make the
   * two halves of AC-6.1 and AC-6.2 contradict each other. The cap counts every
   * kind (spec-00003-FR-3).
   */
  private admit(plan: SessionPlan): void {
    const running = this.running()
    const occupied = running.some(
      (session) => session.plan.kind !== 'ask' && session.info.sourceId === plan.sourceId,
    )
    if (plan.kind !== 'ask' && occupied) {
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
    const session = this.requireTerminal(id)
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
    const session = this.requireTerminal(id)
    this.unlatch(session)
    // The hand-edit note goes in ahead of a frame that carries printable
    // characters **at the start of an empty input line**, and only such a frame
    // consumes it (spec-00006-FR-5, design-00001 §11.4). Three things are no
    // place to splice text into, and each defers without consuming the note:
    // a frame with nothing printable in it — an Escape, an arrow key, a bare
    // Enter; a frame arriving mid-word, where the note would land inside what the
    // user is typing; and a frame that opens a slash command, where it would be
    // read as part of the command name. All three are the concatenation defect
    // issue-00011 fixed. Written straight to the pty rather than through here, so
    // the latch reading above stays keyed to the user's own frame
    // (decision-00011's exclusion list).
    if (session.pendingNote === true && this.noteFits(session, data)) {
      session.pty?.write(HAND_EDIT_NOTE)
      session.pendingNote = undefined
    }
    // The line state follows the frame that was just judged, never precedes it:
    // what the note needs to know is what the line held *before* this frame.
    session.lineFilled = lineFilledAfter(session.lineFilled ?? false, data)
    session.pty?.write(data)
  }

  /**
   * Whether this frame is a place the note may go (design-00001 §11.4): it
   * carries printable content, the input line is empty, and what it opens with is
   * not the `/` of a slash command.
   */
  private noteFits(session: Session, frame: string): boolean {
    const first = firstPrintable(frame)
    return first !== undefined && session.lineFilled !== true && first !== '/'
  }

  /**
   * The user saved a hand edit of a document a cowrite session is writing
   * (spec-00006-FR-5): the note waits on that session until the user's next
   * printable frame takes it. Called after the save has landed, so a refused save
   * leaves no note; a document with no running cowrite has nowhere to put one,
   * which is the ordinary case and no error.
   */
  noteHandEdit(docId: string): void {
    for (const session of this.cowritesOn(docId)) session.pendingNote = true
  }

  /**
   * The cowrite session running on that document, as the front matter it is held
   * to (spec-00006-FR-10): the status lock reads its mere presence and refuses
   * with `doc-busy`, and the editor's guard reads the two values — the identity
   * the session was admitted on, which is the fixed thing a save has to still
   * declare. The **disk** is no reading for that: the agent moves it mid-session,
   * and a save judged against it would let the moved status stand
   * (design-00001 §11.4). Ended sessions are not running, so the lock lifts by
   * itself (spec-00006-AC-10.2).
   */
  cowriteOn(docId: string): { preId: string; preStatus: string } | undefined {
    return this.cowritesOn(docId)[0]?.plan.cowrite
  }

  private cowritesOn(docId: string): Session[] {
    return this.running().filter(
      (session) => session.plan.kind === 'cowrite' && session.info.sourceId === docId,
    )
  }

  /**
   * What the sessions that are still running have claimed (design-00001 §11.3):
   * the first exemption of a cowrite's collapse filter — another session's own
   * product is left where it is for that session's own wrap-up
   * (spec-00006-AC-6.5).
   *
   * The claim is what each kind may write, never «everything that moved since its
   * snapshot»: that reading exempts this session's own strays too, so one
   * concurrent session would switch the whole filter off. The registry is the one
   * that holds the values a claim is computed from — the kind, the document, an
   * advance's expectation and a cowrite's target and baseline — and the doc
   * service turns them into paths, since only it can resolve a document id to the
   * file it lives in.
   *
   * The session that is collapsing has already ended, so it is not in this list
   * and claims nothing of its own; an ask writes nothing and claims nothing.
   */
  runningClaims(): SessionClaim[] {
    return this.running()
      .filter((session) => session.plan.kind !== 'ask')
      .map((session) => ({
        kind: session.plan.kind,
        sourceId: session.plan.sourceId,
        targetPath: session.plan.cowrite?.targetPath,
        targetType: session.plan.expectation?.targetType,
        idPrefix: session.plan.expectation?.idPrefix,
        baseline: session.baseline,
      }))
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
    // Refused rather than dropped, unlike the cases below: an ask has no
    // terminal to be sized at all, and saying so is the point (AC-7.7).
    if (session?.plan.kind === 'ask') this.requireTerminal(id)
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
    for (const session of this.running()) session.kill?.()
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
    session.kill?.()
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
   *
   * An ask narrows the choice to the agents that declare a headless form
   * (spec-00005-FR-2, FR-8): the default is the first of those, and naming one
   * without a declaration is refused for that reason rather than for not
   * existing. A config where none declares one answers no ask at all
   * (spec-00005-AC-7.4).
   */
  private resolveAgent(name: string | undefined, kind: SessionKind): AgentConfig {
    const { agents } = this.options
    if (name === undefined) {
      const chosen = kind === 'ask' ? agents.find((agent) => agent.headless !== undefined) : agents[0]
      if (!chosen) {
        throw new WorkflowError('no agent in the flow config declares a headless form, so nothing can answer an ask')
      }
      return chosen
    }
    const agent = agents.find((candidate) => candidate.name === name)
    if (!agent) {
      throw new WorkflowError(`${JSON.stringify(name)} is not an agent in the flow config`)
    }
    if (kind === 'ask' && agent.headless === undefined) {
      throw new WorkflowError(`${JSON.stringify(name)} declares no headless form, so it cannot answer an ask`)
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
   * The session behind a terminal channel. An ask call runs no pty at all
   * (design-00001 §10.3), so attaching to it, typing at it and sizing it are
   * three refusals of the one thing that is not there (spec-00005-AC-7.7) — and
   * the socket that carries all three is closed on the first of them.
   */
  private requireTerminal(id: string): Session {
    const session = this.require(id)
    if (session.plan.kind === 'ask') {
      throw new NoSessionError(`agent session ${id} is an ask call, which has no terminal`)
    }
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
  private saveHistory(session: Session, endedAt: string, transcript: string): void {
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
        transcript,
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
    session.finished = this.wrapUp(session, endedAt).finally(session.announceEnd)
  }

  /**
   * The wrap-up every session ends on, once. A terminal session says so in its
   * own terminal and hands over its whole transcript; an ask call lands its
   * answer on the thread first and hands over that answer instead
   * (design-00001 §10.3), because that is what a person reading the history of
   * an ask wants to find (spec-00005-AC-5.5). The hook that follows is the same
   * one for both — the refresh the board hears rides on it — and what it does
   * with an ask is nothing, since an ask commits nothing (spec-00005-FR-4).
   */
  private async wrapUp(session: Session, endedAt: string): Promise<void> {
    const terminal = session.plan.kind !== 'ask'
    if (terminal) this.publish(session, `\r\nwhiteboard: session ended with code ${session.info.exitCode}\r\n`)
    // The history records the end state as it is, `terminated` included
    // (design-00001 §7): a restart must not turn a stopped session into one that
    // finished (spec-00001-AC-54.4).
    if (terminal) this.saveHistory(session, endedAt, session.transcript.join(''))
    else await this.landAnswer(session, endedAt)
    // The hook is the commit and, for a cowrite, the collapse filter: a lot of
    // disk and git, any of which may throw. Left to reject it would be an
    // unhandled rejection out of an exit handler — the board's own process, with
    // every other session still running on it — so the failure is recorded on the
    // session and said in its terminal instead, and the end stands either way.
    try {
      const outcome = await this.options.onExit(session.plan, session.baseline)
      session.info.outcome = outcome
      if (terminal) this.publish(session, `whiteboard: ${describe(outcome)}\r\n`)
    } catch (cause) {
      const message = (cause as Error).message
      session.info.outcome = { problems: [message], committed: false, error: message }
      if (terminal) this.publish(session, `whiteboard: the wrap-up failed — ${message}\r\n`)
    }
  }

  /**
   * An ask call's own wrap-up (design-00001 §10.3): read the answer out of what
   * the CLI printed, land it on its thread, then write the history. «Answered»
   * is the one reading — a zero exit whose stdout the capture understands; a
   * non-zero exit and stdout that will not parse are the same failure, and a
   * stopped call is neither (spec-00005-FR-3, FR-7).
   */
  private async landAnswer(session: Session, endedAt: string): Promise<void> {
    const zero = session.info.status === 'exited' && session.info.exitCode === 0
    // Read the way this agent declared it should be, rather than the one way the
    // code happens to hold (design-00001 §10.1).
    const reading = session.agent.headless!.capture
    const captured = zero ? readCapture(reading, session.stdout) : undefined
    const outcome = session.info.status === 'terminated' ? 'terminated' : captured ? 'answered' : 'failed'
    try {
      await this.options.onAskEnd?.(session.plan, {
        outcome,
        answer: captured?.answer,
        // A failed question says why on the thread: «exited 0» is the process's
        // honest story and says nothing about the question, which is the story
        // the ask list tells (spec-00005-FR-7, design-00001 §10.3).
        reason:
          outcome === 'failed'
            ? failureReason(reading, session.stdout, session.stderr, session.info.exitCode ?? 1)
            : undefined,
        resumeId: captured?.resumeId,
        resumed: session.plan.resumeId !== undefined,
      })
    } catch (cause) {
      // A disk that will not take the record costs the user that record and
      // nothing else (the reading spec-00001-AC-54.3 fixes for the history, and
      // the same one here). Left to reject, it would skip the hook below — so no
      // board would ever hear the call ended — and go on to bring the process
      // down as an unhandled rejection.
      const message = (cause as Error).message
      session.info.historyError = message
      session.stderr += `whiteboard: could not save the ask thread — ${message}\n`
    }
    // The transcript of an ask call is the answer it captured; with no answer to
    // capture, what the CLI actually printed is the honest record of the call
    // (spec-00005-AC-5.5).
    this.saveHistory(session, endedAt, captured?.answer ?? session.stdout + session.stderr)
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

/**
 * A CSI sequence: what an arrow key, a mouse report and most of a TUI's chatter
 * are. The parameter bytes include `<=>` as well as the digits and separators —
 * an SGR mouse report is `\x1b[<0;12;5M`, and a class of parameter left out here
 * is a frame read as printable text (spec-00006-AC-5.3).
 */
const CSI_SEQUENCE = /\x1b\[[0-9;?<=>]*[@-~]/g

/**
 * An SS3 sequence: the other single-key escape a terminal sends — F1 to F4, and
 * the arrow keys of a CLI that put the keypad in application mode. Two bytes and
 * a printable character, which is exactly why it has to come off before the
 * control bytes do: `\x1bOA` would otherwise leave `OA` behind.
 */
const SS3_SEQUENCE = /\x1bO[ -~]/g

/** An OSC sequence, terminated by BEL or ST — or by nothing yet, mid-frame. */
const OSC_SEQUENCE = /\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)?/g

/** The control bytes, Escape and Enter among them (design-00001 §11.4). */
const CONTROL_BYTES = /[\x00-\x1f\x7f]/g

/** The frame with its escape sequences off, so what is left is the keys themselves. */
function keys(frame: string): string {
  return frame.replace(OSC_SEQUENCE, '').replace(SS3_SEQUENCE, '').replace(CSI_SEQUENCE, '')
}

/**
 * The first character a terminal frame would put on the input line, or nothing
 * when it would put none — the reading the hand-edit note rides on
 * (spec-00006-FR-5). The sequences come off first and the bare control bytes
 * after them, so what is left is what the user actually typed: an arrow key, an
 * Escape and an empty Enter leave nothing, and the note waits for a frame that
 * leaves something (spec-00006-AC-5.3).
 */
function firstPrintable(frame: string): string | undefined {
  return keys(frame).replace(CONTROL_BYTES, '')[0]
}

/**
 * The input line after this frame (design-00001 §11.4): a submit empties it, and
 * printable characters typed after the last submit fill it. Read off the user's
 * own frames, which are the only writes that reach `write`.
 */
function lineFilledAfter(before: boolean, frame: string): boolean {
  const typed = keys(frame)
  const submit = Math.max(typed.lastIndexOf('\r'), typed.lastIndexOf('\n'))
  const tail = typed.slice(submit + 1).replace(CONTROL_BYTES, '')
  return submit === -1 ? before || tail.length > 0 : tail.length > 0
}

function describe(outcome: SessionOutcome): string {
  if (!outcome.docId) return 'no new document was produced'
  const committed = outcome.committed ? 'committed' : `not committed (${outcome.error ?? 'no changes'})`
  const problems = outcome.problems.length === 0 ? '' : ` — ${outcome.problems.join('; ')}`
  return `${outcome.docId} ${committed}${problems}`
}

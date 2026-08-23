import { existsSync } from 'node:fs'
import { join } from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AgentConfig } from '../src/config.ts'
import { type Expectation, taskInstruction } from '../src/advance.ts'
import { spawnPty } from '../src/pty.ts'
import {
  NoSessionError,
  SessionBusyError,
  SessionManager,
  type SessionOutcome,
  type SessionPlan,
} from '../src/sessionManager.ts'
import { SESSION_WAIT, makeRepo } from './helpers.ts'

const EXPECTATION: Expectation = {
  targetType: 'prd',
  number: 2,
  idPrefix: 'prd-00002-',
  carry: 'parent',
  sourceId: 'idea-00001-x',
}

/** An advance session's plan: the instruction is built by its caller, not by the manager. */
const ADVANCE: SessionPlan = {
  kind: 'advance',
  sourceId: EXPECTATION.sourceId,
  instruction: taskInstruction(EXPECTATION, 'idea/idea-00001-x.md'),
  expectation: EXPECTATION,
}

const OUTCOME: SessionOutcome = { docId: 'prd-00002-new', problems: [], committed: true }

/**
 * What a line-reading stand-in prints once the instruction has been *submitted*:
 * its last line stays in the terminal's line buffer until the Enter that follows
 * the CLI's first output arrives (issue-00011).
 */
const SUBMITTED_TAIL = `got:${ADVANCE.instruction.split('\n').at(-1)}`

/** A stand-in that prints back whatever it is told, so input can be traced to its own session. */
const ECHO = ['-e', "process.stdin.on('data', (d) => console.log('got:' + d.toString().trim()))"]

/** Long enough to still be running when the test looks. */
const HOLD = ['-e', 'setTimeout(() => {}, 5000)']

const managers: SessionManager[] = []

function makeManager(agent: Partial<AgentConfig>, onExit = vi.fn(async () => OUTCOME), maxSessions = 3) {
  const { repoRoot, docsDir } = makeRepo({})
  const manager = new SessionManager({
    agents: [{ name: 'test', command: 'node', args: [], cwd: 'docs', ...agent }],
    maxSessions,
    repoRoot,
    spawn: spawnPty,
    // A real process is slow enough already; the submit's own wait is the one
    // part of it a test can shorten without changing what it proves.
    submitDelayMs: 50,
    onExit,
  })
  managers.push(manager)
  return { manager, onExit, docsDir }
}

/**
 * Collect everything one session prints, from attach onward plus the replayed
 * buffer. The session is named: each has its own buffer and its own listeners
 * (spec-00003-FR-1).
 */
function transcript(manager: SessionManager, id = manager.latest()!.id) {
  const attached = manager.attach(id, (data) => {
    seen += data
  })
  let seen = attached.buffer
  return {
    get text() {
      return seen
    },
    detach: attached.detach,
  }
}

/** The refusal a start threw, so the reason it carries can be read (spec-00003-FR-2, FR-3). */
function refusalOf(start: () => unknown): SessionBusyError {
  try {
    start()
  } catch (error) {
    return error as SessionBusyError
  }
  throw new Error('the start was admitted, so there is no refusal to read')
}

/** A plan of the given kind on the given document; the instruction is its caller's. */
function planFor(kind: SessionPlan['kind'], sourceId: string, instruction = `${kind} ${sourceId}`): SessionPlan {
  return { kind, sourceId, instruction }
}

afterEach(() => {
  for (const manager of managers.splice(0)) manager.stop()
})

describe('start', () => {
  // spec-00001-AC-11.1
  it('runs the configured command as the session', async () => {
    const { manager } = makeManager({ args: ['-e', "console.log('hello from the agent')"] })

    const info = manager.start(ADVANCE)
    const output = transcript(manager)

    expect(info.status).toBe('running')
    expect(info.sourceId).toBe('idea-00001-x')
    await vi.waitFor(() => expect(output.text).toContain('hello from the agent'), SESSION_WAIT)
  })

  // spec-00003-AC-1.1
  it('runs sessions on two different documents at once, both of them interactive', async () => {
    const { manager } = makeManager({ args: ECHO })

    const first = manager.start(planFor('clarify', 'spec-00001-a'))
    const second = manager.start(planFor('ask', 'record-00001-b'))
    const a = transcript(manager, first.id)
    const b = transcript(manager, second.id)

    expect([first.status, second.status]).toEqual(['running', 'running'])
    await vi.waitFor(() => expect(a.text).toContain('got:clarify spec-00001-a'), SESSION_WAIT)
    await vi.waitFor(() => expect(b.text).toContain('got:ask record-00001-b'), SESSION_WAIT)
    manager.write(first.id, 'to a\n')
    manager.write(second.id, 'to b\n')
    await vi.waitFor(() => expect(a.text).toContain('got:to a'), SESSION_WAIT)
    await vi.waitFor(() => expect(b.text).toContain('got:to b'), SESSION_WAIT)
  })

  // spec-00003-AC-1.2
  it('keeps the output and the input of each session to itself', async () => {
    const { manager } = makeManager({ args: ECHO })
    const first = manager.start(planFor('clarify', 'spec-00001-a'))
    const second = manager.start(planFor('ask', 'record-00001-b'))
    const a = transcript(manager, first.id)
    const b = transcript(manager, second.id)
    // Each stand-in echoes only once its own instruction has been submitted, so
    // both are known to be listening before anything is typed at one of them.
    await vi.waitFor(() => expect(a.text).toContain('got:clarify spec-00001-a'), SESSION_WAIT)
    await vi.waitFor(() => expect(b.text).toContain('got:ask record-00001-b'), SESSION_WAIT)

    manager.write(first.id, 'only for a\n')

    await vi.waitFor(() => expect(a.text).toContain('got:only for a'), SESSION_WAIT)
    expect(b.text).not.toContain('only for a')
    expect(a.text).not.toContain('record-00001-b')
  })

  // spec-00003-AC-2.1
  it('refuses a second session on the same document, naming the exclusion, and leaves the first alone', () => {
    const { manager } = makeManager({ args: HOLD })
    const first = manager.start(planFor('ask', 'spec-00001-a'))

    expect(refusalOf(() => manager.start(planFor('clarify', 'spec-00001-a'))).reason).toBe('doc-busy')
    expect(manager.latest()).toEqual(first)
    expect(manager.latest()!.status).toBe('running')
  })

  // spec-00003-AC-2.2
  it('refuses the same document again, adding no session', () => {
    const { manager } = makeManager({ args: HOLD })
    manager.start(planFor('ask', 'spec-00001-a'))
    expect(() => manager.start(planFor('clarify', 'spec-00001-a'))).toThrowError(SessionBusyError)

    expect(() => manager.start(planFor('clarify', 'spec-00001-a'))).toThrowError(SessionBusyError)

    expect(manager.list()).toHaveLength(1)
  })

  // spec-00003-AC-2.6 — the target document of an advance is its source, so two
  // advances from the same document are two sessions on the same document
  it('refuses a second advance from the same source document', () => {
    const { manager } = makeManager({ args: HOLD })
    manager.start(ADVANCE)

    const refusal = refusalOf(() => manager.start({ ...ADVANCE, expectation: { ...EXPECTATION, targetType: 'spec' } }))

    expect(refusal.reason).toBe('doc-busy')
    expect(manager.list()).toHaveLength(1)
  })

  // spec-00003-AC-2.5
  it('starts a new session on a document whose session has ended', async () => {
    const { manager } = makeManager({ args: ['-e', ''] })
    manager.start(ADVANCE)
    await vi.waitFor(() => expect(manager.latest()!.status).toBe('exited'), SESSION_WAIT)

    // The id carries the start time as well as the counter, because it names the
    // session's history files (spec-00001-FR-54); what this asserts is the
    // second session, not the exact stamp.
    expect(manager.start(ADVANCE).id).toMatch(/^\d{4}-\d{2}-\d{2}T[\d-]+Z-2$/)
  })

  // spec-00003-AC-3.1
  it('refuses a start once the cap is reached, naming the cap, and leaves the running ones alone', () => {
    const { manager } = makeManager({ args: HOLD }, undefined, 2)
    const first = manager.start(planFor('ask', 'spec-00001-a'))
    const second = manager.start(planFor('ask', 'spec-00002-b'))

    expect(refusalOf(() => manager.start(planFor('ask', 'record-00001-c'))).reason).toBe('cap-reached')
    expect(manager.list().map((session) => session.id)).toEqual([first.id, second.id])
    expect(manager.list().every((session) => session.status === 'running')).toBe(true)
  })

  /**
   * spec-00003-AC-2.1 with spec-00003-AC-3.1 — both rules hold at once, and the
   * reason given is the more specific one, in the order the disabled entry's
   * hover text follows (design-00001 §7, spec-00001-FR-49).
   */
  it('names the same-document reason when the cap is reached as well', () => {
    const { manager } = makeManager({ args: HOLD }, undefined, 1)
    manager.start(planFor('ask', 'spec-00001-a'))

    expect(refusalOf(() => manager.start(planFor('clarify', 'spec-00001-a'))).reason).toBe('doc-busy')
  })

  // spec-00003-AC-3.2
  it('refuses again at the cap, adding no session', () => {
    const { manager } = makeManager({ args: HOLD }, undefined, 1)
    manager.start(planFor('ask', 'spec-00001-a'))
    expect(() => manager.start(planFor('ask', 'spec-00002-b'))).toThrowError(SessionBusyError)

    expect(() => manager.start(planFor('ask', 'record-00001-c'))).toThrowError(SessionBusyError)

    expect(manager.list()).toHaveLength(1)
  })

  // spec-00003-AC-3.3
  it('admits the next start once one of the capped sessions has ended', async () => {
    const { manager } = makeManager({ args: ['-e', ''] }, undefined, 1)
    manager.start(planFor('ask', 'spec-00001-a'))
    await vi.waitFor(() => expect(manager.latest()!.status).toBe('exited'), SESSION_WAIT)

    expect(manager.start(planFor('ask', 'spec-00002-b')).status).toBe('running')
  })

  /**
   * spec-00003-AC-3.6 — admission and taking the slot happen inside the one
   * synchronous `start`, so two starts arriving at the last slot are ordered by
   * arrival with nothing else to arbitrate them (design-00001 §5).
   */
  it('gives the last slot to whichever start got there first', () => {
    const { manager } = makeManager({ args: HOLD }, undefined, 1)

    const first = manager.start(planFor('ask', 'spec-00001-a'))
    const refusal = refusalOf(() => manager.start(planFor('ask', 'spec-00002-b')))

    expect(refusal.reason).toBe('cap-reached')
    expect(first.status).toBe('running')
  })

  // spec-00001-AC-16.1
  it('reports a CLI missing from PATH in the terminal and never runs the exit hook', async () => {
    const { manager, onExit } = makeManager({ command: 'definitely-not-an-agent-cli' })

    const info = manager.start(ADVANCE)

    expect(info.status).toBe('failed')
    expect(info.error).toMatch(/not found on PATH/)
    expect(transcript(manager).text).toContain('could not start the agent')
    expect(onExit).not.toHaveBeenCalled()
  })

  it('reports a CLI path that is not executable', () => {
    const { manager } = makeManager({ command: './no-such-agent' })
    expect(manager.start(ADVANCE).error).toMatch(/not executable/)
  })

  // spec-00003-AC-3.7 — a session that never started holds no slot, and is still
  // one the panel lists as failed (spec-00003-AC-4.6)
  it('counts a spawn failure towards no cap and lists it as failed', () => {
    // The cap is one, and the first spawn is the one that fails: if the failure
    // held the slot, the second start would be refused rather than admitted.
    let attempts = 0
    const { repoRoot } = makeRepo({})
    const manager = new SessionManager({
      agents: [{ name: 'test', command: 'node', args: [] }],
      maxSessions: 1,
      repoRoot,
      spawn: () => {
        attempts += 1
        if (attempts === 1) throw new Error('agent command not found on PATH: nope')
        return { onData: () => {}, onExit: () => {}, write: () => {}, resize: () => {}, kill: () => {} }
      },
      onExit: async () => OUTCOME,
    })
    managers.push(manager)
    const failed = manager.start(planFor('ask', 'spec-00001-a'))

    const next = manager.start(planFor('ask', 'record-00001-b'))

    expect(failed.status).toBe('failed')
    expect(next.status).toBe('running')
    expect(manager.list().map((session) => [session.id, session.status])).toEqual([
      [failed.id, 'failed'],
      [next.id, 'running'],
    ])
  })

  /**
   * The four kinds share this one registry and one channel each (spec-00003-FR-1);
   * what tells them apart is the kind on the session and the instruction its
   * caller built. Only an advance expects a target type.
   */
  it('carries the kind and the instruction of a clarify or ask session', async () => {
    const { manager } = makeManager({
      args: ['-e', "process.stdin.on('data', (d) => console.log('got:' + d.toString()))"],
    })

    const info = manager.start({ kind: 'clarify', sourceId: 'spec-00001-x', instruction: 'clarify this' })
    const output = transcript(manager)

    expect(info.kind).toBe('clarify')
    expect(info.sourceId).toBe('spec-00001-x')
    expect(info.targetType).toBeUndefined()
    await vi.waitFor(() => expect(output.text).toContain('got:clarify this'), SESSION_WAIT)
  })

  // The exit hook is handed the plan, so it can tell an advance's product check
  // from a clarify or ask that was asked for no new document — and that session's
  // own baseline, which is what scopes its commit (spec-00003-FR-8).
  it('hands the exit hook the plan the session ran on', async () => {
    const plan = { kind: 'ask' as const, sourceId: 'record-00001-x', instruction: 'answer this' }
    const { manager, onExit } = makeManager({ args: ['-e', ''] })

    manager.start(plan)
    await vi.waitFor(() => expect(manager.latest()!.status).toBe('exited'), SESSION_WAIT)
    await manager.whenFinished()

    expect(onExit).toHaveBeenCalledWith(plan, new Map())
  })
})

// spec-00001-AC-13.1
describe('the write-scope constraint', () => {
  it('starts the session under the working directory the flow config constrains it to', () => {
    const spawned: Array<{ command: string; args: string[]; cwd: string }> = []
    const { repoRoot } = makeRepo({})
    const manager = new SessionManager({
      agents: [{ name: 'test', command: 'node', args: ['--version'], cwd: 'docs' }],
      maxSessions: 3,
      repoRoot,
      spawn: (command, args, cwd) => {
        spawned.push({ command, args, cwd })
        return { onData: () => {}, onExit: () => {}, write: () => {}, resize: () => {}, kill: () => {} }
      },
      onExit: async () => OUTCOME,
    })

    manager.start(ADVANCE)

    expect(spawned).toEqual([{ command: 'node', args: ['--version'], cwd: join(repoRoot, 'docs') }])
  })

  it('falls back to the repo root when the agent declares no working directory', () => {
    const spawned: string[] = []
    const { repoRoot } = makeRepo({})
    const manager = new SessionManager({
      agents: [{ name: 'test', command: 'node', args: [] }],
      maxSessions: 3,
      repoRoot,
      spawn: (_command, _args, cwd) => {
        spawned.push(cwd)
        return { onData: () => {}, onExit: () => {}, write: () => {}, resize: () => {}, kill: () => {} }
      },
      onExit: async () => OUTCOME,
    })

    manager.start(ADVANCE)

    expect(spawned).toEqual([join(repoRoot, '.')])
  })
})

describe('a running session', () => {
  // spec-00001-AC-12.1
  it('streams output as it is produced, without a refresh', async () => {
    const { manager } = makeManager({ args: ['-e', "setInterval(() => console.log('tick'), 20)"] })
    manager.start(ADVANCE)
    const output = transcript(manager)

    await vi.waitFor(() => expect(output.text).toContain('tick'), SESSION_WAIT)
  })

  // spec-00001-AC-12.2
  it('forwards terminal input to the CLI', async () => {
    const { manager } = makeManager({
      args: ['-e', "process.stdin.on('data', (d) => console.log('got:' + d.toString().trim()))"],
    })
    manager.start(ADVANCE)
    const output = transcript(manager)
    // The instruction has to be submitted before what the user types is a line
    // of its own; typing into its unsubmitted tail would only lengthen that
    // line (issue-00011).
    await vi.waitFor(() => expect(output.text).toContain(SUBMITTED_TAIL), SESSION_WAIT)

    manager.write(manager.latest()!.id, 'ping\n')

    await vi.waitFor(() => expect(output.text).toContain('got:ping'), SESSION_WAIT)
  })

  // spec-00001-AC-12.5 — the size the terminal reports reaches the process itself,
  // which is what lets a full-screen TUI draw at the size it is being watched at
  // (issue-00009).
  it('resizes the session pty so the CLI sees the terminal size', async () => {
    // The CLI reads the size off its own tty, and it is a process that has to
    // boot before it can read anything — so it keeps saying what it sees rather
    // than reporting the one moment the signal happened to arrive.
    const { manager } = makeManager({
      args: ['-e', "setInterval(() => console.log(process.stdout.columns + 'x' + process.stdout.rows), 50)"],
    })
    manager.start(ADVANCE)
    const output = transcript(manager)

    manager.resize(manager.latest()!.id, 100, 40)

    await vi.waitFor(() => expect(output.text).toContain('100x40'), SESSION_WAIT)
  })

  // spec-00001-AC-11.2 — the task instruction reaches the CLI on startup, whole
  // and submitted: a stand-in that reads by line sees the last line only once
  // the Enter has landed (issue-00011).
  it('sends the task instruction as the first input', async () => {
    const { manager } = makeManager({
      args: ['-e', "process.stdin.on('data', (d) => console.log('got:' + d.toString()))"],
    })
    manager.start(ADVANCE)
    const output = transcript(manager)

    await vi.waitFor(() => expect(output.text).toContain('got:Write one new prd document'), SESSION_WAIT)
    await vi.waitFor(() => expect(output.text).toContain(SUBMITTED_TAIL), SESSION_WAIT)
  })
})

/**
 * spec-00001-AC-11.2, issue-00011 — sending the instruction means submitting it,
 * and the submit is a keypress of its own, not a byte on the end of the text.
 * Two mechanisms sit between the write and the input box: at spawn the terminal
 * is still in the kernel's cooked mode, where ICRNL turns a trailing CR back
 * into the LF that only breaks the line; and a CR arriving inside the same burst
 * as the text is read by the CLI as the tail of a paste, which by design does
 * not send. So the text goes in alone, and the Enter follows once the CLI has
 * spoken — twice, because Enter on an empty box is a no-op and the insurance is
 * free.
 */
describe('submitting the instruction', () => {
  const INSTRUCTION = 'first line\nsecond line'
  const DELAY = 100

  /** A manager on a stand-in pty: what it was written, and the hooks it holds. */
  function stubManager() {
    const written: string[] = []
    const hooks: { data?: (data: string) => void; exit?: (event: { exitCode: number }) => void } = {}
    const { repoRoot } = makeRepo({})
    const manager = new SessionManager({
      agents: [{ name: 'test', command: 'node', args: [] }],
      maxSessions: 3,
      repoRoot,
      submitDelayMs: DELAY,
      spawn: () => ({
        onData: (listener) => void (hooks.data = listener),
        onExit: (listener) => void (hooks.exit = listener),
        write: (data) => void written.push(data),
        resize: () => {},
        kill: () => {},
      }),
      onExit: async () => OUTCOME,
    })
    managers.push(manager)
    manager.start({ kind: 'clarify', sourceId: 'spec-00001-x', instruction: INSTRUCTION })
    return { manager, written, hooks }
  }

  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('writes the instruction with no submit byte of its own, and waits for the CLI', () => {
    const { written } = stubManager()

    expect(written).toEqual([INSTRUCTION])

    // No CLI output yet means no CLI to submit to: time alone presses nothing.
    vi.advanceTimersByTime(100 * DELAY)
    expect(written).toEqual([INSTRUCTION])
  })

  it('presses Enter once the CLI has spoken, and once more as insurance', () => {
    const { written, hooks } = stubManager()

    hooks.data!('welcome to the cli')

    expect(written).toEqual([INSTRUCTION])
    vi.advanceTimersByTime(DELAY)
    expect(written).toEqual([INSTRUCTION, '\r'])
    vi.advanceTimersByTime(DELAY)
    expect(written).toEqual([INSTRUCTION, '\r', '\r'])

    // Everything the session says after that is output, not another prompt.
    hooks.data!('thinking…')
    vi.advanceTimersByTime(100 * DELAY)
    expect(written).toEqual([INSTRUCTION, '\r', '\r'])
  })

  it('presses nothing into a session that has already ended', () => {
    const { written, hooks } = stubManager()
    hooks.data!('welcome to the cli')

    hooks.exit!({ exitCode: 0 })
    // A pty can print its last words after the exit; they are not a prompt.
    hooks.data!('goodbye')
    vi.advanceTimersByTime(100 * DELAY)

    expect(written).toEqual([INSTRUCTION])
  })
})

/**
 * spec-00003-FR-6 — silence read as «waiting on the user». The judgment is about
 * time passing with nothing arriving, so these run on fake timers and a
 * stand-in pty: what has to be shown is that the mark goes up with no output at
 * all, which no amount of scripted output can show. Weak semantics by design —
 * a false positive costs a badge, and the mark drives no transition and no
 * commit (design-00001 §5).
 */
describe('waiting on the user', () => {
  const THRESHOLD = 100

  /** A manager on stand-in ptys, one per session, whose output and exit the test fires. */
  function stubManager(onExit = async () => OUTCOME) {
    const hooks: Array<{ data?: (data: string) => void; exit?: (event: { exitCode: number }) => void }> = []
    const onAwaitingChange = vi.fn()
    const { repoRoot } = makeRepo({})
    const manager = new SessionManager({
      agents: [{ name: 'test', command: 'node', args: [] }],
      maxSessions: 3,
      repoRoot,
      awaitThresholdMs: THRESHOLD,
      spawn: () => {
        const hook: (typeof hooks)[number] = {}
        hooks.push(hook)
        return {
          onData: (listener) => void (hook.data = listener),
          onExit: (listener) => void (hook.exit = listener),
          write: () => {},
          resize: () => {},
          kill: () => {},
        }
      },
      onExit,
      onAwaitingChange,
    })
    managers.push(manager)
    return { manager, hooks, onAwaitingChange }
  }

  /** What the listing the panel reads says about that session (design-00001 §7). */
  const awaiting = (manager: SessionManager, id: string) =>
    manager.list().find((session) => session.id === id)?.awaiting

  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  // spec-00003-AC-6.1's manager half: the row the panel and the badge are drawn
  // from says the session is waiting (the badge itself is the front end's).
  it('marks a running session that has printed nothing for the threshold', () => {
    const { manager, hooks, onAwaitingChange } = stubManager()
    const { id } = manager.start(planFor('clarify', 'spec-00001-a'))
    hooks[0]!.data!('what should this cover?')

    expect(awaiting(manager, id)).toBeFalsy()
    vi.advanceTimersByTime(THRESHOLD)

    expect(awaiting(manager, id)).toBe(true)
    expect(onAwaitingChange).toHaveBeenCalledTimes(1)
  })

  // spec-00003-AC-6.2's manager half — the answer typed in the terminal is
  // followed by output, and output is the whole of «not waiting any more».
  it('takes the mark down as soon as the session speaks again', () => {
    const { manager, hooks, onAwaitingChange } = stubManager()
    const { id } = manager.start(planFor('clarify', 'spec-00001-a'))
    vi.advanceTimersByTime(THRESHOLD)
    expect(awaiting(manager, id)).toBe(true)

    hooks[0]!.data!('thank you, carrying on')

    expect(awaiting(manager, id)).toBe(false)
    // Both flips were announced, and each exactly once: the badge is redrawn off
    // the refresh they trigger (spec-00001-FR-42).
    expect(onAwaitingChange).toHaveBeenCalledTimes(2)
    // The window starts over, so a session that goes quiet again is marked again.
    vi.advanceTimersByTime(THRESHOLD)
    expect(awaiting(manager, id)).toBe(true)
  })

  // spec-00003-AC-6.3's manager half: the mark is gone from the ended row, so
  // nothing is left for the count to be drawn from.
  it('drops the mark when a waiting session ends', async () => {
    const { manager, hooks } = stubManager()
    const { id } = manager.start(planFor('ask', 'record-00001-b'))
    vi.advanceTimersByTime(THRESHOLD)
    expect(awaiting(manager, id)).toBe(true)

    hooks[0]!.exit!({ exitCode: 0 })

    expect(manager.list()[0]!.status).toBe('exited')
    expect(awaiting(manager, id)).toBeFalsy()
    // And nothing arms it again, however long the ended session stays listed.
    vi.advanceTimersByTime(10 * THRESHOLD)
    expect(awaiting(manager, id)).toBeFalsy()
    await manager.whenFinished(id)
  })

  // spec-00003-AC-6.4 — a process that has exited does not enter the judgment,
  // not even while its wrap-up is still running: the silence from the exit
  // onward is the wrap-up's, and there is nobody left to answer anything.
  it('never marks a session whose process has exited with its wrap-up still running', () => {
    // A wrap-up that never finishes, which is the state the case is about.
    const { manager, hooks, onAwaitingChange } = stubManager(() => new Promise<SessionOutcome>(() => {}))
    const { id } = manager.start(planFor('clarify', 'spec-00001-a'))

    hooks[0]!.exit!({ exitCode: 0 })
    // A pty can print its last words after the exit; neither they nor the
    // silence that follows them is the agent waiting on anybody.
    hooks[0]!.data!('goodbye')
    vi.advanceTimersByTime(10 * THRESHOLD)

    expect(awaiting(manager, id)).toBeFalsy()
    expect(manager.list()[0]!.outcome).toBeUndefined()
    // Nothing to announce means nothing for the badge count to change by.
    expect(onAwaitingChange).not.toHaveBeenCalled()
  })
})

describe('exit', () => {
  // spec-00001-AC-12.3
  it('shows the end state and runs the exit hook once the process ends', async () => {
    const { manager, onExit } = makeManager({ args: ['-e', ''] })
    manager.start(ADVANCE)
    const output = transcript(manager)

    await vi.waitFor(() => expect(manager.latest()!.status).toBe('exited'), SESSION_WAIT)
    await manager.whenFinished()

    expect(output.text).toContain('session ended with code 0')
    expect(onExit).toHaveBeenCalledWith(ADVANCE, new Map())
    expect(manager.latest()!.outcome).toEqual(OUTCOME)
    expect(output.text).toContain('prd-00002-new committed')
  })

  it('reports a session that produced nothing', async () => {
    const { manager } = makeManager({ args: ['-e', ''] }, vi.fn(async () => ({ problems: [], committed: false })))
    manager.start(ADVANCE)
    const output = transcript(manager)

    await vi.waitFor(() => expect(manager.latest()!.status).toBe('exited'), SESSION_WAIT)
    await manager.whenFinished()

    expect(output.text).toContain('no new document was produced')
  })

  it('reports an uncommitted product with its problems', async () => {
    const outcome = { docId: 'prd-00002-new', problems: ['parent does not point at idea-00001-x'], committed: false }
    const { manager } = makeManager({ args: ['-e', ''] }, vi.fn(async () => outcome))
    manager.start(ADVANCE)
    const output = transcript(manager)

    await vi.waitFor(() => expect(manager.latest()!.status).toBe('exited'), SESSION_WAIT)
    await manager.whenFinished()

    expect(output.text).toContain('not committed (no changes)')
    expect(output.text).toContain('parent does not point at idea-00001-x')
  })
})

describe('attach', () => {
  // spec-00001-AC-21.2
  it('keeps the session running across a detach and replays the buffer on reattach', async () => {
    const { manager } = makeManager({ args: ['-e', "console.log('before detach'); setInterval(() => {}, 1000)"] })
    manager.start(ADVANCE)

    const first = transcript(manager)
    await vi.waitFor(() => expect(first.text).toContain('before detach'), SESSION_WAIT)
    first.detach()

    expect(manager.latest()!.status).toBe('running')
    expect(transcript(manager).text).toContain('before detach')
  })

  // spec-00001-AC-21.1 — the session keeps working, not just running
  it('keeps writing files after the last terminal detaches', async () => {
    const { manager, docsDir } = makeManager({
      args: [
        '-e',
        "console.log('started'); setTimeout(() => require('fs').writeFileSync('after-detach.md', 'written'), 150)",
      ],
    })
    manager.start(ADVANCE)
    const attached = transcript(manager)
    await vi.waitFor(() => expect(attached.text).toContain('started'), SESSION_WAIT)

    attached.detach()

    await vi.waitFor(() => expect(existsSync(join(docsDir, 'after-detach.md'))).toBe(true), SESSION_WAIT)
  })

  it('refuses to attach to or write to a session the registry does not know', () => {
    const { manager } = makeManager({})
    expect(() => manager.attach('nope', () => {})).toThrowError(NoSessionError)
    expect(() => manager.write('nope', 'x')).toThrowError(NoSessionError)
  })

  /**
   * spec-00003-AC-9.1's server half at the registry: a session with no terminal
   * on it goes on running, and each is reattached by its own id with its own
   * output behind it (spec-00001-FR-21 extended to several sessions).
   */
  it('holds two unattached sessions and replays each one its own output', async () => {
    const { manager } = makeManager({ args: ECHO })
    const first = manager.start(planFor('clarify', 'spec-00001-a'))
    const second = manager.start(planFor('ask', 'record-00001-b'))
    await vi.waitFor(
      () => expect(transcript(manager, second.id).text).toContain('got:ask record-00001-b'),
      SESSION_WAIT,
    )

    const a = transcript(manager, first.id)
    const b = transcript(manager, second.id)

    expect(manager.list().map((session) => session.status)).toEqual(['running', 'running'])
    await vi.waitFor(() => expect(a.text).toContain('got:clarify spec-00001-a'), SESSION_WAIT)
    expect(b.text).toContain('got:ask record-00001-b')
    expect(b.text).not.toContain('spec-00001-a')
  })

  /**
   * spec-00001-AC-49.4, spec-00003-AC-5.5 — judged per session: an id nobody ever
   * held and an id whose session has ended are both refused, and another session
   * running changes neither answer.
   */
  it('refuses to stop a session it does not know or one that has ended', async () => {
    const { manager } = makeManager({ args: ['-e', ''] })
    const ended = manager.start(planFor('ask', 'spec-00001-a'))
    await vi.waitFor(() => expect(manager.latest()!.status).toBe('exited'), SESSION_WAIT)
    await manager.whenFinished(ended.id)

    await expect(manager.terminate('nope')).rejects.toThrowError(NoSessionError)
    await expect(manager.terminate(ended.id)).rejects.toThrowError(NoSessionError)
  })

  // spec-00001-AC-49.1 — the stop ends that session, and only that one; the end
  // state says it was stopped rather than that it ran out (spec-00003-AC-5.3)
  it('stops the session it is given and leaves the other one running', async () => {
    const { manager } = makeManager({ args: HOLD })
    const first = manager.start(planFor('ask', 'spec-00001-a'))
    const second = manager.start(planFor('ask', 'record-00001-b'))

    const stopped = await manager.terminate(first.id)

    expect(stopped.status).toBe('terminated')
    expect(manager.list().map((session) => session.status)).toEqual(['terminated', 'running'])
    expect(second.status).toBe('running')
  })

  /**
   * A size frame is not an instruction to anyone: one that lands with nothing
   * running — the window between a session ending and a terminal noticing — has
   * nothing to resize, and saying so as an error would only break the reconnect.
   */
  it('ignores a resize with no session behind it', () => {
    const { manager } = makeManager({})
    expect(() => manager.resize('nope', 100, 40)).not.toThrow()
  })

  it('ignores a resize that arrives after the session has exited', async () => {
    const { manager } = makeManager({ args: ['-e', ''] })
    manager.start(ADVANCE)
    await vi.waitFor(() => expect(manager.latest()!.status).toBe('exited'), SESSION_WAIT)

    expect(() => manager.resize(manager.latest()!.id, 100, 40)).not.toThrow()
  })

  it('lists nothing and reports no session before the first start', () => {
    const { manager } = makeManager({})
    expect(manager.latest()).toBeNull()
    expect(manager.list()).toEqual([])
    expect(manager.whenFinished()).resolves.toBeUndefined()
    expect(manager.whenFinished('nope')).resolves.toBeUndefined()
  })
})

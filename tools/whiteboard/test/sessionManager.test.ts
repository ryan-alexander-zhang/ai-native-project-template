import { existsSync } from 'node:fs'
import { join } from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { AgentConfig } from '../src/config.ts'
import { type Expectation, taskInstruction } from '../src/advance.ts'
import { spawnPty } from '../src/pty.ts'
import { SessionBusyError, SessionManager, type SessionOutcome, type SessionPlan } from '../src/sessionManager.ts'
import { SESSION_WAIT, makeRepo } from './helpers.ts'

const EXPECTATION: Expectation = {
  targetType: 'prd',
  idPrefix: 'prd-00002-',
  carry: 'parent',
  sourceId: 'idea-00001-x',
}

/** An advance session's plan: the instruction is built by its caller, not by the manager. */
const ADVANCE: SessionPlan = {
  kind: 'advance',
  sourceId: EXPECTATION.sourceId,
  instruction: taskInstruction(EXPECTATION),
  expectation: EXPECTATION,
}

const OUTCOME: SessionOutcome = { docId: 'prd-00002-new', problems: [], committed: true }

const managers: SessionManager[] = []

function makeManager(agent: Partial<AgentConfig>, onExit = vi.fn(async () => OUTCOME)) {
  const { repoRoot, docsDir } = makeRepo({})
  const manager = new SessionManager({
    agent: { name: 'test', command: 'node', args: [], cwd: 'docs', ...agent },
    repoRoot,
    spawn: spawnPty,
    onExit,
  })
  managers.push(manager)
  return { manager, onExit, docsDir }
}

/** Collect everything the session prints, from attach onward plus the replayed buffer. */
function transcript(manager: SessionManager) {
  const attached = manager.attach((data) => {
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

  // spec-00001-AC-18.1
  it('refuses a second session and leaves the running one alone', async () => {
    const { manager } = makeManager({ args: ['-e', 'setTimeout(() => {}, 5000)'] })
    const first = manager.start(ADVANCE)

    expect(() => manager.start(ADVANCE)).toThrowError(SessionBusyError)
    expect(manager.current()).toEqual(first)
    expect(manager.current()!.status).toBe('running')
  })

  it('allows a new session once the previous one has exited', async () => {
    const { manager } = makeManager({ args: ['-e', ''] })
    manager.start(ADVANCE)
    await vi.waitFor(() => expect(manager.current()!.status).toBe('exited'), SESSION_WAIT)

    expect(manager.start(ADVANCE).id).toBe('s2')
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

  /**
   * The three kinds share this one manager, this one slot and this one terminal
   * (spec-00001-FR-18); what tells them apart is the kind on the session and the
   * instruction its caller built. Only an advance expects a target type.
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
  // from a clarify or ask that was asked for no new document.
  it('hands the exit hook the plan the session ran on', async () => {
    const plan = { kind: 'ask' as const, sourceId: 'record-00001-x', instruction: 'answer this' }
    const { manager, onExit } = makeManager({ args: ['-e', ''] })

    manager.start(plan)
    await vi.waitFor(() => expect(manager.current()!.status).toBe('exited'), SESSION_WAIT)
    await manager.whenFinished()

    expect(onExit).toHaveBeenCalledWith(plan)
  })
})

// spec-00001-AC-13.1
describe('the write-scope constraint', () => {
  it('starts the session under the working directory the flow config constrains it to', () => {
    const spawned: Array<{ command: string; args: string[]; cwd: string }> = []
    const { repoRoot } = makeRepo({})
    const manager = new SessionManager({
      agent: { name: 'test', command: 'node', args: ['--version'], cwd: 'docs' },
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
      agent: { name: 'test', command: 'node', args: [] },
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

    manager.write('ping\n')

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

    manager.resize(100, 40)

    await vi.waitFor(() => expect(output.text).toContain('100x40'), SESSION_WAIT)
  })

  /**
   * spec-00001-AC-11.2 — sending it is submitting it. The CLI's input box reads
   * LF as a line break inside the box and only CR as the Enter that submits, so
   * the instruction's own newlines stay LF and the last byte is CR (issue-00011).
   */
  it('ends the instruction with the carriage return that submits it', () => {
    const written: string[] = []
    const { repoRoot } = makeRepo({})
    const manager = new SessionManager({
      agent: { name: 'test', command: 'node', args: [] },
      repoRoot,
      spawn: () => ({
        onData: () => {},
        onExit: () => {},
        write: (data) => void written.push(data),
        resize: () => {},
        kill: () => {},
      }),
      onExit: async () => OUTCOME,
    })

    manager.start({ kind: 'clarify', sourceId: 'spec-00001-x', instruction: 'first line\nsecond line' })

    expect(written).toEqual(['first line\nsecond line\r'])
  })

  // spec-00001-AC-11.2 — the task instruction reaches the CLI on startup
  it('sends the task instruction as the first input', async () => {
    const { manager } = makeManager({
      args: ['-e', "process.stdin.on('data', (d) => console.log('got:' + d.toString()))"],
    })
    manager.start(ADVANCE)
    const output = transcript(manager)

    await vi.waitFor(() => expect(output.text).toContain('got:Write one new prd document'), SESSION_WAIT)
  })
})

describe('exit', () => {
  // spec-00001-AC-12.3
  it('shows the end state and runs the exit hook once the process ends', async () => {
    const { manager, onExit } = makeManager({ args: ['-e', ''] })
    manager.start(ADVANCE)
    const output = transcript(manager)

    await vi.waitFor(() => expect(manager.current()!.status).toBe('exited'), SESSION_WAIT)
    await manager.whenFinished()

    expect(output.text).toContain('session ended with code 0')
    expect(onExit).toHaveBeenCalledWith(ADVANCE)
    expect(manager.current()!.outcome).toEqual(OUTCOME)
    expect(output.text).toContain('prd-00002-new committed')
  })

  it('reports a session that produced nothing', async () => {
    const { manager } = makeManager({ args: ['-e', ''] }, vi.fn(async () => ({ problems: [], committed: false })))
    manager.start(ADVANCE)
    const output = transcript(manager)

    await vi.waitFor(() => expect(manager.current()!.status).toBe('exited'), SESSION_WAIT)
    await manager.whenFinished()

    expect(output.text).toContain('no new document was produced')
  })

  it('reports an uncommitted product with its problems', async () => {
    const outcome = { docId: 'prd-00002-new', problems: ['parent does not point at idea-00001-x'], committed: false }
    const { manager } = makeManager({ args: ['-e', ''] }, vi.fn(async () => outcome))
    manager.start(ADVANCE)
    const output = transcript(manager)

    await vi.waitFor(() => expect(manager.current()!.status).toBe('exited'), SESSION_WAIT)
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

    expect(manager.current()!.status).toBe('running')
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

  it('refuses to attach or write when no session was ever started', () => {
    const { manager } = makeManager({})
    expect(() => manager.attach(() => {})).toThrowError(SessionBusyError)
    expect(() => manager.write('x')).toThrowError(SessionBusyError)
  })

  /**
   * A size frame is not an instruction to anyone: one that lands with nothing
   * running — the window between a session ending and a terminal noticing — has
   * nothing to resize, and saying so as an error would only break the reconnect.
   */
  it('ignores a resize with no session behind it', () => {
    const { manager } = makeManager({})
    expect(() => manager.resize(100, 40)).not.toThrow()
  })

  it('ignores a resize that arrives after the session has exited', async () => {
    const { manager } = makeManager({ args: ['-e', ''] })
    manager.start(ADVANCE)
    await vi.waitFor(() => expect(manager.current()!.status).toBe('exited'), SESSION_WAIT)

    expect(() => manager.resize(100, 40)).not.toThrow()
  })

  it('reports no current session before the first start', () => {
    const { manager } = makeManager({})
    expect(manager.current()).toBeNull()
    expect(manager.whenFinished()).resolves.toBeUndefined()
  })
})

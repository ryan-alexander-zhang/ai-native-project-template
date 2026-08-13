import { afterEach, describe, expect, it, vi } from 'vitest'
import type { AgentConfig } from '../src/config.ts'
import type { Expectation } from '../src/advance.ts'
import { spawnPty } from '../src/pty.ts'
import { SessionBusyError, SessionManager, type SessionOutcome } from '../src/sessionManager.ts'
import { makeRepo } from './helpers.ts'

const EXPECTATION: Expectation = {
  targetType: 'prd',
  idPrefix: 'prd-00002-',
  carry: 'parent',
  sourceId: 'idea-00001-x',
}

const OUTCOME: SessionOutcome = { docId: 'prd-00002-new', problems: [], committed: true }

const managers: SessionManager[] = []

function makeManager(agent: Partial<AgentConfig>, onExit = vi.fn(async () => OUTCOME)) {
  const { repoRoot } = makeRepo({})
  const manager = new SessionManager({
    agent: { name: 'test', command: 'node', args: [], cwd: 'docs', ...agent },
    repoRoot,
    spawn: spawnPty,
    onExit,
  })
  managers.push(manager)
  return { manager, onExit }
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

    const info = manager.start(EXPECTATION)
    const output = transcript(manager)

    expect(info.status).toBe('running')
    expect(info.sourceId).toBe('idea-00001-x')
    await vi.waitFor(() => expect(output.text).toContain('hello from the agent'))
  })

  // spec-00001-AC-18.1
  it('refuses a second session and leaves the running one alone', async () => {
    const { manager } = makeManager({ args: ['-e', 'setTimeout(() => {}, 5000)'] })
    const first = manager.start(EXPECTATION)

    expect(() => manager.start(EXPECTATION)).toThrowError(SessionBusyError)
    expect(manager.current()).toEqual(first)
    expect(manager.current()!.status).toBe('running')
  })

  it('allows a new session once the previous one has exited', async () => {
    const { manager } = makeManager({ args: ['-e', ''] })
    manager.start(EXPECTATION)
    await vi.waitFor(() => expect(manager.current()!.status).toBe('exited'))

    expect(manager.start(EXPECTATION).id).toBe('s2')
  })

  // spec-00001-AC-16.1 and AC-16.2
  it('reports a CLI missing from PATH in the terminal and never runs the exit hook', async () => {
    const { manager, onExit } = makeManager({ command: 'definitely-not-an-agent-cli' })

    const info = manager.start(EXPECTATION)

    expect(info.status).toBe('failed')
    expect(info.error).toMatch(/not found on PATH/)
    expect(transcript(manager).text).toContain('could not start the agent')
    expect(onExit).not.toHaveBeenCalled()
  })

  it('reports a CLI path that is not executable', () => {
    const { manager } = makeManager({ command: './no-such-agent' })
    expect(manager.start(EXPECTATION).error).toMatch(/not executable/)
  })
})

describe('a running session', () => {
  // spec-00001-AC-12.1
  it('streams output as it is produced, without a refresh', async () => {
    const { manager } = makeManager({ args: ['-e', "setInterval(() => console.log('tick'), 20)"] })
    manager.start(EXPECTATION)
    const output = transcript(manager)

    await vi.waitFor(() => expect(output.text).toContain('tick'))
  })

  // spec-00001-AC-12.2
  it('forwards terminal input to the CLI', async () => {
    const { manager } = makeManager({
      args: ['-e', "process.stdin.on('data', (d) => console.log('got:' + d.toString().trim()))"],
    })
    manager.start(EXPECTATION)
    const output = transcript(manager)

    manager.write('ping\n')

    await vi.waitFor(() => expect(output.text).toContain('got:ping'))
  })

  // spec-00001-AC-11.2 — the task instruction reaches the CLI on startup
  it('sends the task instruction as the first input', async () => {
    const { manager } = makeManager({
      args: ['-e', "process.stdin.on('data', (d) => console.log('got:' + d.toString()))"],
    })
    manager.start(EXPECTATION)
    const output = transcript(manager)

    await vi.waitFor(() => expect(output.text).toContain('got:Write one new prd document'))
  })
})

describe('exit', () => {
  // spec-00001-AC-12.3 and AC-12.4
  it('shows the end state and runs the exit hook once the process ends', async () => {
    const { manager, onExit } = makeManager({ args: ['-e', ''] })
    manager.start(EXPECTATION)
    const output = transcript(manager)

    await vi.waitFor(() => expect(manager.current()!.status).toBe('exited'))
    await manager.whenFinished()

    expect(output.text).toContain('session ended with code 0')
    expect(onExit).toHaveBeenCalledWith(EXPECTATION)
    expect(manager.current()!.outcome).toEqual(OUTCOME)
    expect(output.text).toContain('prd-00002-new committed')
  })

  it('reports a session that produced nothing', async () => {
    const { manager } = makeManager({ args: ['-e', ''] }, vi.fn(async () => ({ problems: [], committed: false })))
    manager.start(EXPECTATION)
    const output = transcript(manager)

    await vi.waitFor(() => expect(manager.current()!.status).toBe('exited'))
    await manager.whenFinished()

    expect(output.text).toContain('no new document was produced')
  })

  it('reports an uncommitted product with its problems', async () => {
    const outcome = { docId: 'prd-00002-new', problems: ['parent does not point at idea-00001-x'], committed: false }
    const { manager } = makeManager({ args: ['-e', ''] }, vi.fn(async () => outcome))
    manager.start(EXPECTATION)
    const output = transcript(manager)

    await vi.waitFor(() => expect(manager.current()!.status).toBe('exited'))
    await manager.whenFinished()

    expect(output.text).toContain('not committed (no changes)')
    expect(output.text).toContain('parent does not point at idea-00001-x')
  })
})

describe('attach', () => {
  // spec-00001-AC-21.1 and AC-21.2
  it('keeps the session running across a detach and replays the buffer on reattach', async () => {
    const { manager } = makeManager({ args: ['-e', "console.log('before detach'); setInterval(() => {}, 1000)"] })
    manager.start(EXPECTATION)

    const first = transcript(manager)
    await vi.waitFor(() => expect(first.text).toContain('before detach'))
    first.detach()

    expect(manager.current()!.status).toBe('running')
    expect(transcript(manager).text).toContain('before detach')
  })

  it('refuses to attach or write when no session was ever started', () => {
    const { manager } = makeManager({})
    expect(() => manager.attach(() => {})).toThrowError(SessionBusyError)
    expect(() => manager.write('x')).toThrowError(SessionBusyError)
  })

  it('reports no current session before the first start', () => {
    const { manager } = makeManager({})
    expect(manager.current()).toBeNull()
    expect(manager.whenFinished()).resolves.toBeUndefined()
  })
})

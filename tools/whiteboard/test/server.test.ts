import type { Server } from 'node:http'
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { Board } from '../src/server.ts'
import { ptySpawner, spawnPty } from '../src/pty.ts'
import { clarifyStatePath } from '../src/sessionTasks.ts'
import {
  SESSION_WAIT,
  armWatch,
  commitCount,
  doc,
  lastCommitFiles,
  lastCommitMessage,
  makeRepo,
  testConfig,
} from './helpers.ts'

// The waits in this file are bounded by things outside it — a spawned agent
// process (SESSION_WAIT) and a file watch crossing the OS (SIGNAL_WAIT) — and
// under a suite running its files side by side both outlast the default five
// seconds, which would cut the wait off before its own timeout is reached.
vi.setConfig({ testTimeout: 30_000 })

const DRAFT_IDEA = doc({ id: 'idea-00001-x', type: 'idea', status: 'draft' }, '# Idea X\n')
const ACTIVE_IDEA = doc({ id: 'idea-00001-x', type: 'idea', status: 'active' }, '# Idea X\n')
const servers: Server[] = []
const watching: Board[] = []

/** Start a board on an ephemeral port and give back a fetch bound to it. */
function boardOn(files: Record<string, string>, agentArgs = ['-e', ''], command?: string, spawn = spawnPty) {
  const { repoRoot, docsDir } = makeRepo(files)
  const config = testConfig()
  config.agents[0] = { ...config.agents[0]!, args: agentArgs, ...(command ? { command } : {}) }
  const board = new Board({ repoRoot, docsDir, config, spawn })
  const server = board.listen(0)
  servers.push(server)
  // The http server only announces its close once every socket has gone, and a
  // test may leave one open; letting go of the file watches here keeps a suite
  // of several dozen boards from running the process out of descriptors.
  watching.push(board)
  const port = (server.address() as { port: number }).port

  const call = async (method: string, path: string, body?: unknown) => {
    const response = await fetch(`http://127.0.0.1:${port}${path}`, {
      method,
      headers: body ? { 'content-type': 'application/json' } : undefined,
      body: body ? JSON.stringify(body) : undefined,
    })
    return { status: response.status, body: await response.json() }
  }
  return { board, repoRoot, docsDir, port, call }
}

/** The request function `boardOn` hands back, for helpers that take one. */
type BoardCall = ReturnType<typeof boardOn>['call']

afterEach(async () => {
  for (const server of servers.splice(0)) server.close()
  await Promise.all(watching.splice(0).map((board) => board.watcher.close()))
})

describe('GET /api/graph', () => {
  it('serves the nodes, edges, and issues', async () => {
    const { call } = boardOn({ 'idea/a.md': ACTIVE_IDEA })
    const { status, body } = await call('GET', '/api/graph')

    expect(status).toBe(200)
    expect(body.nodes).toHaveLength(1)
    expect(body.nodes[0].id).toBe('idea-00001-x')
    expect(body.issues).toEqual([])
  })
})

describe('GET /api/config', () => {
  it('serves the flow config the board is running', async () => {
    const { call } = boardOn({})
    const { body } = await call('GET', '/api/config')
    expect(body.types.idea).toBe('living')
  })
})

describe('document reads', () => {
  it('serves the whole file with its hash', async () => {
    const { call } = boardOn({ 'idea/a.md': ACTIVE_IDEA })
    const { body } = await call('GET', '/api/docs/idea-00001-x')

    expect(body.content).toBe(ACTIVE_IDEA)
    expect(body.hash).toMatch(/^[0-9a-f]{64}$/)
  })

  it('answers 409 for a document that is not there', async () => {
    const { call } = boardOn({})
    const { status, body } = await call('GET', '/api/docs/idea-09999-ghost')

    expect(status).toBe(409)
    expect(body.error).toMatch(/refresh the board/)
  })

  // spec-00001-AC-6.1
  it('serves the legal transitions', async () => {
    const { call } = boardOn({ 'idea/a.md': DRAFT_IDEA })
    expect((await call('GET', '/api/docs/idea-00001-x/transitions')).body).toEqual(['active', 'archived'])
  })

  // spec-00001-AC-10.2
  it('serves the next-step candidates', async () => {
    const { call } = boardOn({ 'idea/a.md': ACTIVE_IDEA })
    expect((await call('GET', '/api/docs/idea-00001-x/next-steps')).body).toEqual([
      { next: 'prd', carry: 'parent' },
      { next: 'spec', carry: 'parent' },
    ])
  })
})

// spec-00001-FR-31 … FR-33 over the wire; the payload contract is design-00001 §7.
describe('GET /api/docs/:id/items', () => {
  const SPEC = doc(
    { id: 'spec-00001-x', type: 'spec', status: 'active' },
    [
      '# Spec\n',
      '- **spec-00001-FR-1** (Event) the board loads the graph.',
      '- **spec-00001-FR-2** (Unwanted) a broken document is marked.\n',
      '- **spec-00001-AC-1.1** (spec-00001-FR-1)',
      '  Given docs',
      '  When the board loads',
      '  Then a node per document',
      '- **spec-00001-AC-2.1** (spec-00001-FR-2)',
      '  Given a broken document',
      '  When the board loads',
      '  Then the node is marked\n',
    ].join('\n'),
  )
  const record = (status: string, rows: string[]) =>
    doc(
      { id: 'record-00001-x', type: 'record', status },
      ['# Record\n', '| GWT id | 测试 | 结果 |', '| --- | --- | --- |', ...rows, ''].join('\n'),
    )

  it('serves the items with their criteria, rows, and coverage', async () => {
    const { call } = boardOn({
      'spec/a.md': SPEC,
      'record/r.md': record('active', ['| spec-00001-AC-1.1 | draws a node | pass |']),
    })

    const { status, body } = await call('GET', '/api/docs/spec-00001-x/items')

    expect(status).toBe(200)
    expect(body.items.map((found: { id: string }) => found.id)).toEqual(['spec-00001-FR-1', 'spec-00001-FR-2'])
    expect(body.items[0].criteria[0].rows).toEqual([
      { recordId: 'record-00001-x', targetId: 'spec-00001-AC-1.1', test: 'draws a node', result: 'pass' },
    ])
    // spec-00001-AC-32.1 and AC-32.2 as the panel will read them
    expect(body.items.map((found: { coverage: string }) => found.coverage)).toEqual(['verified', 'uncovered'])
    expect(body.diagnostics).toEqual([])
  })

  // spec-00001-AC-32.5 — a record's own status says nothing about the evidence
  it('counts a row from a draft record', async () => {
    const { call } = boardOn({
      'spec/a.md': SPEC,
      'record/r.md': record('draft', ['| spec-00001-AC-1.1 | draws a node | pass |']),
    })

    const { body } = await call('GET', '/api/docs/spec-00001-x/items')

    expect(body.items[0].coverage).toBe('verified')
  })

  // spec-00001-AC-33.1
  it('serves a row that verifies a criterion this document does not have as a diagnostic', async () => {
    const { call } = boardOn({
      'spec/a.md': SPEC,
      'record/r.md': record('active', ['| spec-00001-AC-99.1 | a stale test | pass |']),
    })

    const { body } = await call('GET', '/api/docs/spec-00001-x/items')

    expect(body.diagnostics).toMatchObject([
      { kind: 'unattributable', recordId: 'record-00001-x', declaredId: 'spec-00001-AC-99.1' },
    ])
    expect(body.items[0].criteria).toHaveLength(1)
  })

  it('serves no items for a type that declares none', async () => {
    const { call } = boardOn({ 'idea/a.md': ACTIVE_IDEA })
    expect((await call('GET', '/api/docs/idea-00001-x/items')).body).toEqual({ items: [], diagnostics: [] })
  })

  it('answers 409 for a document that is not there', async () => {
    expect((await boardOn({}).call('GET', '/api/docs/spec-09999-ghost/items')).status).toBe(409)
  })
})

describe('PUT /api/docs/:id', () => {
  // spec-00001-AC-4.1 and AC-14.1
  it('saves the edited content and commits it', async () => {
    const { call, docsDir, repoRoot } = boardOn({ 'idea/a.md': ACTIVE_IDEA })
    const { body: opened } = await call('GET', '/api/docs/idea-00001-x')

    const { status, body } = await call('PUT', '/api/docs/idea-00001-x', {
      content: `${opened.content}more\n`,
      baseHash: opened.hash,
    })

    expect(status).toBe(200)
    expect(body.committed).toBe(true)
    expect(readFileSync(join(docsDir, 'idea/a.md'), 'utf8')).toBe(`${ACTIVE_IDEA}more\n`)
    expect(lastCommitMessage(repoRoot)).toBe('wb(edit): idea-00001-x')
  })

  // spec-00001-AC-5.1
  it('answers 409 when the file changed under the editor', async () => {
    const { call, docsDir } = boardOn({ 'idea/a.md': ACTIVE_IDEA })
    const { body: opened } = await call('GET', '/api/docs/idea-00001-x')
    writeFileSync(join(docsDir, 'idea/a.md'), `${ACTIVE_IDEA}from an agent\n`)

    const { status } = await call('PUT', '/api/docs/idea-00001-x', { content: 'mine', baseHash: opened.hash })

    expect(status).toBe(409)
  })
})

describe('POST /api/docs/:id/status', () => {
  it('applies a legal transition', async () => {
    const { call } = boardOn({ 'idea/a.md': DRAFT_IDEA })
    const { status, body } = await call('POST', '/api/docs/idea-00001-x/status', { to: 'active' })

    expect(status).toBe(200)
    expect(body).toEqual({ committed: true, status: 'active' })
  })

  // spec-00001-AC-7.1
  it('answers 422 for an illegal transition', async () => {
    const { call } = boardOn({ 'idea/a.md': DRAFT_IDEA })
    const { status, body } = await call('POST', '/api/docs/idea-00001-x/status', { to: 'resolved' })

    expect(status).toBe(422)
    expect(body.error).toMatch(/not a legal transition/)
  })
})

describe('POST /api/docs/:id/review', () => {
  // spec-00001-AC-8.1
  it('accepts a draft into active', async () => {
    const { call } = boardOn({ 'idea/a.md': DRAFT_IDEA })
    expect((await call('POST', '/api/docs/idea-00001-x/review', { action: 'accept' })).body.status).toBe('active')
  })

  // Clarify left this endpoint with the eighth round (decision-00006): it is a
  // session, started at /api/sessions/clarify, and nothing here writes for it.
  it('answers 422 for the clarify action the review endpoint no longer carries', async () => {
    const { call, docsDir } = boardOn({ 'idea/a.md': DRAFT_IDEA })

    const { status, body } = await call('POST', '/api/docs/idea-00001-x/review', {
      action: 'clarify',
      questions: ['who owns this?'],
    })

    expect(status).toBe(422)
    expect(body.error).toMatch(/is not a review action/)
    expect(readFileSync(join(docsDir, 'idea/a.md'), 'utf8')).toBe(DRAFT_IDEA)
  })

  // spec-00001-AC-8.3
  it('answers 422 when the document is not a draft', async () => {
    const { call } = boardOn({ 'idea/a.md': ACTIVE_IDEA })
    expect((await call('POST', '/api/docs/idea-00001-x/review', { action: 'accept' })).status).toBe(422)
  })

  // spec-00001-AC-19.1
  it('answers 409 when the document was deleted', async () => {
    const { call, docsDir } = boardOn({ 'idea/a.md': DRAFT_IDEA })
    rmSync(join(docsDir, 'idea/a.md'))

    expect((await call('POST', '/api/docs/idea-00001-x/review', { action: 'accept' })).status).toBe(409)
  })
})

describe('sessions', () => {
  it('reports no current session before the first advance', async () => {
    const { call } = boardOn({})
    expect((await call('GET', '/api/sessions')).body).toEqual({ current: null })
  })

  // spec-00001-AC-11.1
  it('starts an advance the flow config allows', async () => {
    const { call, board } = boardOn({ 'idea/a.md': ACTIVE_IDEA })

    const { status, body } = await call('POST', '/api/sessions', { sourceId: 'idea-00001-x', targetType: 'prd' })

    expect(status).toBe(200)
    expect(body.targetType).toBe('prd')
    await vi.waitFor(() => expect(board.sessions.current()!.status).toBe('exited'), SESSION_WAIT)
    await board.sessions.whenFinished()
  })

  it('answers 422 for a step the flow config does not declare', async () => {
    const { call } = boardOn({ 'idea/a.md': ACTIVE_IDEA })
    const { status, body } = await call('POST', '/api/sessions', { sourceId: 'idea-00001-x', targetType: 'plan' })

    expect(status).toBe(422)
    expect(body.error).toMatch(/is not a next step/)
  })

  it('answers 422 when the request names no document', async () => {
    const { call } = boardOn({})
    expect((await call('POST', '/api/sessions', {})).status).toBe(422)
  })

  // spec-00001-AC-16.2
  it('leaves no commit behind when the agent CLI never starts', async () => {
    const { call, repoRoot } = boardOn({ 'idea/a.md': ACTIVE_IDEA })
    const before = commitCount(repoRoot)

    await call('POST', '/api/sessions', { sourceId: 'idea-00001-x', targetType: 'prd' })

    expect(commitCount(repoRoot)).toBe(before)
  })

  // spec-00001-AC-18.1
  it('answers 409 while a session is running', async () => {
    const { call } = boardOn({ 'idea/a.md': ACTIVE_IDEA }, ['-e', 'setTimeout(() => {}, 5000)'])
    await call('POST', '/api/sessions', { sourceId: 'idea-00001-x', targetType: 'prd' })

    const { status, body } = await call('POST', '/api/sessions', { sourceId: 'idea-00001-x', targetType: 'prd' })

    expect(status).toBe(409)
    expect(body.error).toMatch(/already running/)
  })

  // spec-00001-AC-21.2 — a reconnecting browser finds the session
  it('reports the running session so a reconnecting board can find it', async () => {
    const { call } = boardOn({ 'idea/a.md': ACTIVE_IDEA }, ['-e', 'setTimeout(() => {}, 5000)'])
    await call('POST', '/api/sessions', { sourceId: 'idea-00001-x', targetType: 'prd' })

    const { body } = await call('GET', '/api/sessions')

    expect(body.current.status).toBe('running')
    expect(body.current.sourceId).toBe('idea-00001-x')
  })
})

/**
 * The other two session kinds over the wire (spec-00001-FR-9 and FR-47): the same
 * channel, the same one slot, each with its own ruling. Nothing here writes a
 * document — the session's agent does, and the board commits what it left.
 */
describe('clarify and ask sessions', () => {
  const DRAFT_SPEC = doc({ id: 'spec-00001-b', type: 'spec', status: 'draft' }, '# Spec\n')
  const ACTIVE_RECORD = doc({ id: 'record-00001-r', type: 'record', status: 'active' }, '# Record\n')
  const BROKEN = doc({ id: 'nope', type: 'spec', status: 'draft' }, '# Broken\n')
  const HOLD = ['-e', 'setTimeout(() => {}, 5000)']
  /** An agent that revises the document it was started on, then exits. */
  const REVISE = (path: string) => ['-e', `require('fs').appendFileSync('${path}', '\\nrevised\\n')`]

  // spec-00001-AC-9.1
  it('starts a clarify session on a draft of a clarifiable type', async () => {
    const { call, board } = boardOn({ 'spec/b.md': DRAFT_SPEC }, HOLD)

    const { status, body } = await call('POST', '/api/sessions/clarify', { docId: 'spec-00001-b' })

    expect(status).toBe(200)
    expect(body.kind).toBe('clarify')
    expect(body.status).toBe('running')
    expect(board.sessions.current()!.sourceId).toBe('spec-00001-b')
  })

  // spec-00001-AC-9.2
  it('answers 422 and starts nothing for a document that is not draft', async () => {
    const { call, board } = boardOn({ 'idea/a.md': ACTIVE_IDEA })

    const { status, body } = await call('POST', '/api/sessions/clarify', { docId: 'idea-00001-x' })

    expect(status).toBe(422)
    expect(body.error).toMatch(/applies to a draft/)
    expect(board.sessions.current()).toBeNull()
  })

  // spec-00001-AC-9.4
  it('answers 422 and starts nothing for a draft of a type that is not clarifiable', async () => {
    const { call, board } = boardOn({
      'record/r.md': doc({ id: 'record-00001-r', type: 'record', status: 'draft' }, '# Record\n'),
    })

    const { status, body } = await call('POST', '/api/sessions/clarify', { docId: 'record-00001-r' })

    expect(status).toBe(422)
    expect(body.error).toMatch(/does not apply to a record/)
    expect(board.sessions.current()).toBeNull()
  })

  // spec-00001-AC-47.1 — ask is not a review action: any type, any status
  it('starts an ask session on an active record', async () => {
    const { call, board } = boardOn({ 'record/r.md': ACTIVE_RECORD }, HOLD)

    const { status, body } = await call('POST', '/api/sessions/ask', { docId: 'record-00001-r' })

    expect(status).toBe(200)
    expect(body.kind).toBe('ask')
    expect(board.sessions.current()!.status).toBe('running')
  })

  // spec-00001-AC-47.5
  it('answers 422 and starts nothing for an anomalous document', async () => {
    const { call, board } = boardOn({ 'spec/broken.md': BROKEN })

    const { status, body } = await call('POST', '/api/sessions/ask', { docId: 'nope' })

    expect(status).toBe(422)
    expect(body.error).toMatch(/front matter problems/)
    expect(board.sessions.current()).toBeNull()
  })

  // spec-00001-AC-19.2
  it('answers 409 and starts nothing when the target document was deleted', async () => {
    const { call, board, docsDir } = boardOn({ 'spec/b.md': DRAFT_SPEC })
    rmSync(join(docsDir, 'spec/b.md'))

    for (const path of ['/api/sessions/ask', '/api/sessions/clarify']) {
      const { status, body } = await call('POST', path, { docId: 'spec-00001-b' })

      expect(status).toBe(409)
      expect(body.error).toMatch(/refresh the board/)
    }
    expect(board.sessions.current()).toBeNull()
  })

  // spec-00001-AC-18.2 — one slot for all three kinds
  it('answers 409 for an ask while a clarify session is running, leaving it alone', async () => {
    const { call, board } = boardOn({ 'spec/b.md': DRAFT_SPEC, 'record/r.md': ACTIVE_RECORD }, HOLD)
    await call('POST', '/api/sessions/clarify', { docId: 'spec-00001-b' })

    const { status, body } = await call('POST', '/api/sessions/ask', { docId: 'record-00001-r' })

    expect(status).toBe(409)
    expect(body.error).toMatch(/already running/)
    expect(board.sessions.current()).toMatchObject({ kind: 'clarify', sourceId: 'spec-00001-b', status: 'running' })
  })

  it('answers 422 when the request names no document', async () => {
    const { call } = boardOn({ 'spec/b.md': DRAFT_SPEC })
    expect((await call('POST', '/api/sessions/clarify', {})).status).toBe(422)
    expect((await call('POST', '/api/sessions/ask', {})).status).toBe(422)
  })

  // spec-00001-AC-16.3 and AC-16.4
  it('reports a missing agent CLI in the terminal, with no commit and no state file', async () => {
    const { call, board, repoRoot } = boardOn({ 'spec/b.md': DRAFT_SPEC }, ['-e', ''], 'definitely-not-an-agent-cli')
    const commits = commitCount(repoRoot)

    const { body } = await call('POST', '/api/sessions/clarify', { docId: 'spec-00001-b' })

    expect(body.status).toBe('failed')
    expect(body.error).toMatch(/not found on PATH/)
    expect(board.sessions.attach(() => {}).buffer).toContain('could not start the agent')
    expect(commitCount(repoRoot)).toBe(commits)
    expect(existsSync(join(repoRoot, clarifyStatePath('spec-00001-b')))).toBe(false)
  })

  // spec-00001-AC-14.8, and AC-46.4 for what stays out of that commit
  it('commits what a clarify session wrote under docs, and nothing outside it', async () => {
    const { call, board, repoRoot } = boardOn({ 'spec/b.md': DRAFT_SPEC }, REVISE('spec/b.md'))
    mkdirSync(join(repoRoot, '.whiteboard/clarify'), { recursive: true })
    writeFileSync(join(repoRoot, clarifyStatePath('spec-00001-b')), '{"answered":1}')

    await call('POST', '/api/sessions/clarify', { docId: 'spec-00001-b' })
    await vi.waitFor(() => expect(board.sessions.current()!.status).toBe('exited'), SESSION_WAIT)
    await board.sessions.whenFinished()

    expect(lastCommitMessage(repoRoot)).toBe('wb(clarify): spec-00001-b')
    expect(lastCommitFiles(repoRoot)).toEqual(['docs/spec/b.md'])
  })

  // spec-00001-AC-14.7
  it('commits what an ask session wrote under docs', async () => {
    const { call, board, repoRoot } = boardOn({ 'record/r.md': ACTIVE_RECORD }, REVISE('record/r.md'))

    await call('POST', '/api/sessions/ask', { docId: 'record-00001-r' })
    await vi.waitFor(() => expect(board.sessions.current()!.status).toBe('exited'), SESSION_WAIT)
    await board.sessions.whenFinished()

    expect(lastCommitMessage(repoRoot)).toBe('wb(ask): record-00001-r')
  })

  // spec-00001-AC-47.2 — a discussion that concluded nothing changes nothing
  it('leaves the document and the history alone when an ask session wrote nothing', async () => {
    const { call, board, repoRoot, docsDir } = boardOn({ 'spec/b.md': DRAFT_SPEC })
    const commits = commitCount(repoRoot)

    await call('POST', '/api/sessions/ask', { docId: 'spec-00001-b' })
    await vi.waitFor(() => expect(board.sessions.current()!.status).toBe('exited'), SESSION_WAIT)
    await board.sessions.whenFinished()

    expect(readFileSync(join(docsDir, 'spec/b.md'), 'utf8')).toBe(DRAFT_SPEC)
    expect(commitCount(repoRoot)).toBe(commits)
    expect(board.sessions.current()!.outcome!.committed).toBe(false)
  })
})

/**
 * spec-00001-FR-49 (issue-00010): the one way out of a session that will not end
 * by itself. The exit wrap-up is the ordinary one — end state, the kind's commit,
 * a refreshed board — so what is new here is only the way in.
 */
describe('DELETE /api/sessions', () => {
  const DRAFT_SPEC = doc({ id: 'spec-00001-b', type: 'spec', status: 'draft' }, '# Spec\n')
  const HOLD = ['-e', 'setTimeout(() => {}, 5000)']
  /**
   * A session process that ignores SIGHUP and then sleeps past any test, so the
   * only thing that can end it is the escalation (issue-00012). It reports its
   * own pid before sleeping: the file appearing is how the test knows the
   * ignore is in place, and the pid in it is what it asks the OS about later.
   */
  const PID_FILE = join(tmpdir(), `whiteboard-deaf-to-hup-${process.pid}.pid`)
  const DEAF_TO_HUP = ['-c', `trap '' HUP; echo $$ > ${PID_FILE}; sleep 60`]
  /** The grace this test waits out; production holds seconds (KILL_GRACE_MS). */
  const TEST_GRACE_MS = 200

  /** Whether that process is still around; a signal of 0 only asks. */
  function isRunning(pid: number): boolean {
    try {
      process.kill(pid, 0)
      return true
    } catch {
      return false
    }
  }

  /** A clarify agent that revises the document it was started on, then hangs. */
  const REVISE_AND_HOLD = [
    '-e',
    "require('fs').appendFileSync('spec/b.md', '\\nrevised\\n'); setTimeout(() => {}, 5000)",
  ]

  /** A clarify session on the draft spec, held until it has actually written. */
  async function clarifyThatWrote(call: BoardCall, docsDir: string) {
    await call('POST', '/api/sessions/clarify', { docId: 'spec-00001-b' })
    await vi.waitFor(
      () => expect(readFileSync(join(docsDir, 'spec/b.md'), 'utf8')).toContain('revised'),
      SESSION_WAIT,
    )
  }

  // spec-00001-AC-49.1
  it('ends the running process and leaves the end state in the terminal', async () => {
    const { call, board } = boardOn({ 'idea/a.md': ACTIVE_IDEA }, HOLD)
    await call('POST', '/api/sessions', { sourceId: 'idea-00001-x', targetType: 'prd' })

    const { status, body } = await call('DELETE', '/api/sessions')

    expect(status).toBe(200)
    expect(body.status).toBe('exited')
    expect(board.sessions.current()!.status).toBe('exited')
    expect(board.sessions.attach(() => {}).buffer).toContain('session ended with code')
  })

  // spec-00001-AC-49.2 — a stopped session's writings are committed under its kind
  it('commits what the stopped session wrote, named by its kind', async () => {
    const { call, repoRoot, docsDir } = boardOn({ 'spec/b.md': DRAFT_SPEC }, REVISE_AND_HOLD)
    await clarifyThatWrote(call, docsDir)

    await call('DELETE', '/api/sessions')

    expect(lastCommitMessage(repoRoot)).toBe('wb(clarify): spec-00001-b')
    expect(lastCommitFiles(repoRoot)).toEqual(['docs/spec/b.md'])
  })

  // spec-00001-AC-49.3 — the slot is free again, which is the point of stopping
  it('lets a new session start once the stuck one has been stopped', async () => {
    const { call } = boardOn({ 'idea/a.md': ACTIVE_IDEA }, HOLD)
    await call('POST', '/api/sessions', { sourceId: 'idea-00001-x', targetType: 'prd' })

    await call('DELETE', '/api/sessions')

    expect((await call('POST', '/api/sessions', { sourceId: 'idea-00001-x', targetType: 'prd' })).status).toBe(200)
  })

  // spec-00001-AC-49.6 — stopping is not an action that can be taken twice: the
  // second attempt must not put a second commit on the same wrap-up.
  it('refuses a second stop of the same session and commits nothing again', async () => {
    const { call, repoRoot, docsDir } = boardOn({ 'spec/b.md': DRAFT_SPEC }, REVISE_AND_HOLD)
    await clarifyThatWrote(call, docsDir)
    await call('DELETE', '/api/sessions')
    const commits = commitCount(repoRoot)

    const { status, body } = await call('DELETE', '/api/sessions')

    expect(status).toBe(404)
    expect(body.error).toMatch(/no running agent session/)
    expect(commitCount(repoRoot)).toBe(commits)
  })

  // spec-00001-AC-49.9 — the progress a clarify made outlives the stop; only an
  // accept clears the state file (spec-00001-FR-46).
  it('leaves the clarify state file in place when the session is stopped', async () => {
    const { call, repoRoot, docsDir } = boardOn({ 'spec/b.md': DRAFT_SPEC }, REVISE_AND_HOLD)
    const statePath = join(repoRoot, clarifyStatePath('spec-00001-b'))
    mkdirSync(join(repoRoot, '.whiteboard/clarify'), { recursive: true })
    writeFileSync(statePath, '{"answered":1}')
    await clarifyThatWrote(call, docsDir)

    await call('DELETE', '/api/sessions')

    expect(existsSync(statePath)).toBe(true)
    expect(readFileSync(statePath, 'utf8')).toBe('{"answered":1}')
  })

  /**
   * spec-00001-AC-49.10 — the reason Stop exists is a session that will not
   * listen, so the polite signal alone is not an end (issue-00012): the wait has
   * to be bounded by an escalation rather than by the process's manners.
   */
  it('ends a session whose process ignores the polite signal', async () => {
    rmSync(PID_FILE, { force: true })
    const { call, board } = boardOn({ 'idea/a.md': ACTIVE_IDEA }, DEAF_TO_HUP, 'bash', ptySpawner(TEST_GRACE_MS))
    await call('POST', '/api/sessions', { sourceId: 'idea-00001-x', targetType: 'prd' })
    await vi.waitFor(() => expect(existsSync(PID_FILE)).toBe(true), SESSION_WAIT)
    const pid = Number(readFileSync(PID_FILE, 'utf8').trim())
    expect(isRunning(pid)).toBe(true)

    const { status, body } = await call('DELETE', '/api/sessions')

    expect(status).toBe(200)
    expect(body.status).toBe('exited')
    expect(board.sessions.attach(() => {}).buffer).toContain('session ended with code')
    expect(isRunning(pid)).toBe(false)
    rmSync(PID_FILE, { force: true })
  })

  // spec-00001-AC-49.4
  it('answers 404 when no session is running', async () => {
    const { call } = boardOn({})

    const { status, body } = await call('DELETE', '/api/sessions')

    expect(status).toBe(404)
    expect(body.error).toMatch(/no running agent session/)
  })

  // spec-00001-AC-49.4 — a session that already ended is not one to stop either
  it('answers 404 for a session that has already exited', async () => {
    const { call, board } = boardOn({ 'idea/a.md': ACTIVE_IDEA })
    await call('POST', '/api/sessions', { sourceId: 'idea-00001-x', targetType: 'prd' })
    await vi.waitFor(() => expect(board.sessions.current()!.status).toBe('exited'), SESSION_WAIT)
    await board.sessions.whenFinished()

    expect((await call('DELETE', '/api/sessions')).status).toBe(404)
  })
})

/**
 * spec-00001-FR-12's size half (issue-00009). The protocol keeps the two kinds of
 * message apart by frame type: every text frame is stdin verbatim, and a binary
 * frame carries `{cols, rows}` — so no keystroke can be read as a size and no
 * size as a keystroke.
 */
describe('terminal size frames', () => {
  /** A board whose pty is a stand-in recording what it was told to become. */
  function boardWithRecordingPty() {
    const { repoRoot, docsDir } = makeRepo({ 'idea/a.md': ACTIVE_IDEA })
    const sizes: Array<{ cols: number; rows: number }> = []
    const typed: string[] = []
    const board = new Board({
      repoRoot,
      docsDir,
      config: testConfig(),
      spawn: () => ({
        onData: () => {},
        onExit: () => {},
        write: (data: string) => void typed.push(data),
        kill: () => {},
        resize: (cols: number, rows: number) => void sizes.push({ cols, rows }),
      }),
    })
    const server = board.listen(0)
    servers.push(server)
    watching.push(board)
    board.sessions.start({ kind: 'ask', sourceId: 'idea-00001-x', instruction: 'answer this' })
    return { board, sizes, typed, port: (server.address() as { port: number }).port }
  }

  /** An open terminal socket on that board. */
  async function attach(port: number) {
    const socket = new WebSocket(`ws://127.0.0.1:${port}/api/terminal`)
    await new Promise<void>((resolve) => socket.addEventListener('open', () => resolve()))
    return socket
  }

  /** The size frame as the front end sends it: binary, holding the JSON pair. */
  function sizeFrame(cols: number, rows: number): Buffer {
    return Buffer.from(JSON.stringify({ cols, rows }))
  }

  // spec-00001-AC-12.5
  it('resizes the session pty to the size the attached terminal reports', async () => {
    const { sizes, port } = boardWithRecordingPty()
    const socket = await attach(port)

    socket.send(sizeFrame(100, 40))

    await vi.waitFor(() => expect(sizes).toEqual([{ cols: 100, rows: 40 }]))
    socket.close()
  })

  // spec-00001-AC-12.6 — the panel moved, so the size the pty holds moves with it
  it('resizes the pty again for every later size frame', async () => {
    const { sizes, port } = boardWithRecordingPty()
    const socket = await attach(port)

    socket.send(sizeFrame(100, 40))
    socket.send(sizeFrame(80, 24))

    await vi.waitFor(() =>
      expect(sizes).toEqual([
        { cols: 100, rows: 40 },
        { cols: 80, rows: 24 },
      ]),
    )
    socket.close()
  })

  it('keeps a size frame out of stdin, and a keystroke out of the size', async () => {
    const { sizes, typed, port } = boardWithRecordingPty()
    const socket = await attach(port)

    socket.send(sizeFrame(100, 40))
    socket.send('{"cols":9,"rows":9}')

    // The instruction is the session's own first write, submitted with CR
    // (issue-00011); the keystroke frame is forwarded exactly as it arrived.
    await vi.waitFor(() => expect(typed).toEqual(['answer this\r', '{"cols":9,"rows":9}']))
    expect(sizes).toEqual([{ cols: 100, rows: 40 }])
    socket.close()
  })

  it('drops a control frame it cannot read as a size, and carries on', async () => {
    const { sizes, typed, port } = boardWithRecordingPty()
    const socket = await attach(port)

    socket.send(Buffer.from('not json at all'))
    socket.send(Buffer.from(JSON.stringify({ cols: 'wide', rows: null })))
    socket.send(sizeFrame(100, 40))

    await vi.waitFor(() => expect(sizes).toEqual([{ cols: 100, rows: 40 }]))
    expect(typed).toEqual(['answer this\r'])
    socket.close()
  })
})

describe('the terminal socket', () => {
  /** Collect frames until `match` shows up, then hand back the socket. */
  function connect(port: number) {
    const socket = new WebSocket(`ws://127.0.0.1:${port}/api/terminal`)
    let text = ''
    socket.addEventListener('message', (event) => {
      text += event.data
    })
    return {
      socket,
      get text() {
        return text
      },
      opened: new Promise<void>((resolve) => socket.addEventListener('open', () => resolve())),
      closed: new Promise<void>((resolve) => socket.addEventListener('close', () => resolve())),
    }
  }

  // spec-00001-AC-12.1 and AC-12.2
  it('streams session output and forwards what the user types', async () => {
    const { call, port } = boardOn({ 'idea/a.md': ACTIVE_IDEA }, [
      '-e',
      "process.stdin.on('data', (d) => console.log('got:' + d.toString().trim()))",
    ])
    await call('POST', '/api/sessions', { sourceId: 'idea-00001-x', targetType: 'prd' })

    const terminal = connect(port)
    await terminal.opened
    await vi.waitFor(() => expect(terminal.text).toContain('got:Write one new prd document'), SESSION_WAIT)

    terminal.socket.send('ping\n')
    await vi.waitFor(() => expect(terminal.text).toContain('got:ping'), SESSION_WAIT)
    terminal.socket.close()
  })

  // spec-00001-AC-21.2
  it('replays what the session already printed to a reconnecting terminal', async () => {
    const { call, port } = boardOn({ 'idea/a.md': ACTIVE_IDEA }, [
      '-e',
      "console.log('printed early'); setTimeout(() => {}, 5000)",
    ])
    await call('POST', '/api/sessions', { sourceId: 'idea-00001-x', targetType: 'prd' })

    const first = connect(port)
    await first.opened
    await vi.waitFor(() => expect(first.text).toContain('printed early'), SESSION_WAIT)
    first.socket.close()
    await first.closed

    const second = connect(port)
    await second.opened
    await vi.waitFor(() => expect(second.text).toContain('printed early'), SESSION_WAIT)
    second.socket.close()
  })

  it('closes a terminal opened before any session started', async () => {
    const { port } = boardOn({})
    const terminal = connect(port)
    await terminal.closed
    expect(terminal.text).toBe('')
  })
})

/**
 * spec-00001-FR-42: the board is told about a change instead of being asked to
 * look for one. What the user sees of it is the front end's half (web/test);
 * this half is «a write under docs/ becomes one signal on the wire».
 */
describe('the docs-change socket', () => {
  const OTHER_IDEA = doc({ id: 'idea-00002-y', type: 'idea', status: 'draft' }, '# Idea Y\n')
  /** Longer than the debounce window, so «nothing arrived» has had its chance to. */
  const SETTLE = 400
  /**
   * A signal crosses a file watch, a debounce and a socket; a whole suite of
   * boards running at once stretches all three, and none of them is what any of
   * these tests is measuring.
   */
  const SIGNAL_WAIT = { timeout: 10_000, interval: 25 }

  /** A board whose watch is demonstrably delivering, so no write below can be missed. */
  async function watchingBoard(files: Record<string, string> = { 'idea/a.md': ACTIVE_IDEA }, agentArgs?: string[]) {
    const open = agentArgs ? boardOn(files, agentArgs) : boardOn(files)
    await armWatch(open.board.watcher, open.docsDir)
    return open
  }

  /**
   * A board's end of the channel: every frame counts as one signal, whatever it
   * carries. Connected means «the server is following it»: the handshake is
   * answered a moment before the server subscribes, and a write into that gap
   * would go unheard — a board reconnecting into it re-reads anyway
   * (spec-00001-FR-43), so it is the test, not the board, that must not race.
   */
  async function subscribe(open: { port: number; board: Board }) {
    const socket = new WebSocket(`ws://127.0.0.1:${open.port}/api/events`)
    let signals = 0
    socket.addEventListener('message', () => {
      signals += 1
    })
    sockets.push(socket)
    const followers = open.board.watcher.followers + 1
    await new Promise<void>((resolve) => socket.addEventListener('open', () => resolve()))
    await vi.waitFor(() => expect(open.board.watcher.followers).toBe(followers), SIGNAL_WAIT)
    return {
      socket,
      get signals() {
        return signals
      },
    }
  }

  const sockets: WebSocket[] = []
  afterEach(() => {
    for (const socket of sockets.splice(0)) socket.close()
  })

  // spec-00001-AC-42.1 on the wire, and AC-42.3 for the other direction
  it('signals a document written under docs, and one deleted', async () => {
    const open = await watchingBoard()
    const { docsDir, call } = open
    const board = await subscribe(open)

    writeFileSync(join(docsDir, 'idea/b.md'), OTHER_IDEA)

    await vi.waitFor(() => expect(board.signals).toBe(1), SIGNAL_WAIT)
    expect((await call('GET', '/api/graph')).body.nodes).toHaveLength(2)

    rmSync(join(docsDir, 'idea/b.md'))

    await vi.waitFor(() => expect(board.signals).toBe(2), SIGNAL_WAIT)
    expect((await call('GET', '/api/graph')).body.nodes).toHaveLength(1)
  })

  // spec-00001-AC-42.4 — a burst is one signal, and the graph is the disk's
  it('folds a burst of writes into a single signal', async () => {
    const open = await watchingBoard()
    const { docsDir, call } = open
    const board = await subscribe(open)

    for (const name of ['b', 'c', 'd']) {
      writeFileSync(join(docsDir, `idea/${name}.md`), OTHER_IDEA.replace('00002', `0000${name.charCodeAt(0) - 95}`))
    }

    await vi.waitFor(() => expect(board.signals).toBe(1), SIGNAL_WAIT)
    await new Promise((resolve) => setTimeout(resolve, SETTLE))
    expect(board.signals).toBe(1)
    expect((await call('GET', '/api/graph')).body.nodes).toHaveLength(4)
  })

  // spec-00001-AC-42.5
  it('says nothing about a file outside docs', async () => {
    const open = await watchingBoard()
    const { repoRoot, docsDir } = open
    const board = await subscribe(open)

    writeFileSync(join(repoRoot, 'tools-notes.md'), 'not a document')
    await new Promise((resolve) => setTimeout(resolve, SETTLE))

    expect(board.signals).toBe(0)
    // …and the channel was live the whole time, which is what makes the silence mean something.
    writeFileSync(join(docsDir, 'idea/b.md'), OTHER_IDEA)
    await vi.waitFor(() => expect(board.signals).toBe(1), SIGNAL_WAIT)
  })

  // spec-00001-AC-42.7 — no quiet period while an agent is at work
  it('signals what a running session writes, before it ends', async () => {
    const open = await watchingBoard({ 'idea/a.md': ACTIVE_IDEA }, [
      '-e',
      `require('fs').mkdirSync('prd',{recursive:true});
       setTimeout(() => require('fs').writeFileSync('prd/half-written.md', '# half\\n'), 50);
       setTimeout(() => {}, 5000)`,
    ])
    const { port, board } = open
    const watching = await subscribe(open)

    await fetch(`http://127.0.0.1:${port}/api/sessions`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ sourceId: 'idea-00001-x', targetType: 'prd' }),
    })

    await vi.waitFor(() => expect(watching.signals).toBeGreaterThanOrEqual(1), SESSION_WAIT)
    expect(board.sessions.current()!.status).toBe('running')
  })

  // spec-00001-AC-42.8 — nobody listening is not a failure
  it('carries on with no board connected at all', async () => {
    const { docsDir, call } = await watchingBoard()

    writeFileSync(join(docsDir, 'idea/b.md'), OTHER_IDEA)
    await new Promise((resolve) => setTimeout(resolve, SETTLE))

    const { status, body } = await call('GET', '/api/graph')
    expect(status).toBe(200)
    expect(body.nodes).toHaveLength(2)
  })

  // spec-00001-AC-42.9
  it('signals every connected board', async () => {
    const open = await watchingBoard()
    const first = await subscribe(open)
    const second = await subscribe(open)

    writeFileSync(join(open.docsDir, 'idea/b.md'), OTHER_IDEA)

    await vi.waitFor(() => expect([first.signals, second.signals]).toEqual([1, 1]), SIGNAL_WAIT)
  })

  it('drops a board that has gone away and keeps signalling the rest', async () => {
    const open = await watchingBoard()
    const staying = await subscribe(open)
    const leaving = await subscribe(open)
    leaving.socket.close()
    await vi.waitFor(() => expect(open.board.watcher.followers).toBe(1), SIGNAL_WAIT)

    writeFileSync(join(open.docsDir, 'idea/b.md'), OTHER_IDEA)

    await vi.waitFor(() => expect(staying.signals).toBe(1), SIGNAL_WAIT)
    expect(leaving.signals).toBe(0)
  })

  it('refuses an upgrade on any other path', async () => {
    const { port } = await watchingBoard()
    const stray = new WebSocket(`ws://127.0.0.1:${port}/api/nothing-here`)
    stray.addEventListener('error', () => {})

    await new Promise<void>((resolve) => stray.addEventListener('close', () => resolve()))
  })
})

describe('when a session ends', () => {
  const writeProduct = (content: string) =>
    `-e|require('fs').mkdirSync('prd',{recursive:true});require('fs').writeFileSync('prd/new.md',${JSON.stringify(content)})`

  // spec-00001-AC-12.4, AC-17.2, AC-14.4
  it('commits the product and finds nothing wrong with it', async () => {
    const product = doc({ id: 'prd-00001-new', type: 'prd', status: 'draft', parent: 'idea-00001-x' }, '# New\n')
    const { call, board, repoRoot } = boardOn({ 'idea/a.md': ACTIVE_IDEA }, writeProduct(product).split('|'))

    await call('POST', '/api/sessions', { sourceId: 'idea-00001-x', targetType: 'prd' })
    await vi.waitFor(() => expect(board.sessions.current()!.status).toBe('exited'), SESSION_WAIT)
    await board.sessions.whenFinished()

    expect(board.sessions.current()!.outcome).toEqual({
      docId: 'prd-00001-new',
      problems: [],
      committed: true,
      error: undefined,
    })
    expect(lastCommitMessage(repoRoot)).toBe('wb(advance): prd-00001-new')
    expect((await call('GET', '/api/graph')).body.nodes.find((n: { id: string }) => n.id === 'prd-00001-new').ok).toBe(
      true,
    )
  })

  // spec-00001-AC-17.1
  it('marks a product that does not point back at its source', async () => {
    const product = doc({ id: 'prd-00001-new', type: 'prd', status: 'draft' }, '# New\n')
    const { call, board } = boardOn({ 'idea/a.md': ACTIVE_IDEA }, writeProduct(product).split('|'))

    await call('POST', '/api/sessions', { sourceId: 'idea-00001-x', targetType: 'prd' })
    await vi.waitFor(() => expect(board.sessions.current()!.status).toBe('exited'), SESSION_WAIT)
    await board.sessions.whenFinished()

    const node = (await call('GET', '/api/graph')).body.nodes.find((n: { id: string }) => n.id === 'prd-00001-new')
    expect(node.ok).toBe(false)
    expect(node.problems).toContain('parent does not point at idea-00001-x')
  })

  it('reports a session that produced nothing', async () => {
    const { call, board } = boardOn({ 'idea/a.md': ACTIVE_IDEA })

    await call('POST', '/api/sessions', { sourceId: 'idea-00001-x', targetType: 'prd' })
    await vi.waitFor(() => expect(board.sessions.current()!.status).toBe('exited'), SESSION_WAIT)
    await board.sessions.whenFinished()

    expect(board.sessions.current()!.outcome).toEqual({ problems: [], committed: false, error: undefined })
  })

  // spec-00001-AC-14.5 through the whole lifecycle: the snapshot is only worth
  // anything if it is taken before the agent runs, and this is what says so
  // (issue-00008).
  it('commits the product and leaves the dirt the session started from behind', async () => {
    const product = doc({ id: 'prd-00001-new', type: 'prd', status: 'draft', parent: 'idea-00001-x' }, '# New\n')
    const { call, board, docsDir, repoRoot } = boardOn({ 'idea/a.md': ACTIVE_IDEA }, writeProduct(product).split('|'))
    const dirty = `${ACTIVE_IDEA}an edit nobody committed\n`
    writeFileSync(join(docsDir, 'idea/a.md'), dirty)

    await call('POST', '/api/sessions', { sourceId: 'idea-00001-x', targetType: 'prd' })
    await vi.waitFor(() => expect(board.sessions.current()!.status).toBe('exited'), SESSION_WAIT)
    await board.sessions.whenFinished()

    expect(lastCommitFiles(repoRoot)).toEqual(['docs/prd/new.md'])
    expect(readFileSync(join(docsDir, 'idea/a.md'), 'utf8')).toBe(dirty)
  })

  // spec-00001-AC-14.6 — a session that wrote nothing leaves the history alone,
  // however dirty the tree it ran on was.
  it('makes no commit when a session on a dirty tree produces nothing', async () => {
    const { call, board, docsDir, repoRoot } = boardOn({ 'idea/a.md': ACTIVE_IDEA })
    writeFileSync(join(docsDir, 'idea/a.md'), `${ACTIVE_IDEA}an edit nobody committed\n`)
    const commits = commitCount(repoRoot)

    await call('POST', '/api/sessions', { sourceId: 'idea-00001-x', targetType: 'prd' })
    await vi.waitFor(() => expect(board.sessions.current()!.status).toBe('exited'), SESSION_WAIT)
    await board.sessions.whenFinished()

    expect(commitCount(repoRoot)).toBe(commits)
    expect(board.sessions.current()!.outcome!.committed).toBe(false)
  })
})

/**
 * spec-00001-FR-41, the second half: what a session produced is read against the
 * item grammar too, and what drifts is shown the way FR-40 shows everything else
 * — a diagnostic, never a refusal to commit.
 */
describe('what a session produced, read against the item grammar', () => {
  const writeSpec = (body: string) =>
    [
      '-e',
      `require('fs').mkdirSync('spec',{recursive:true});require('fs').writeFileSync('spec/new.md',${JSON.stringify(
        doc({ id: 'spec-00001-new', type: 'spec', status: 'draft', parent: 'idea-00001-x' }, body),
      )})`,
    ]

  const WELL_FORMED = [
    '# New spec',
    '',
    '- **spec-00001-FR-1** (Event) the requirement',
    '',
    '- **spec-00001-AC-1.1** (spec-00001-FR-1)',
    '  Given a board When it loads Then it works',
    '',
  ].join('\n')

  const DRIFTED = WELL_FORMED.replace(
    '- **spec-00001-FR-1** (Event) the requirement',
    '**spec-00001-FR-1** (Event) the requirement',
  )

  async function advanceInto(body: string) {
    const board = boardOn({ 'idea/a.md': ACTIVE_IDEA }, writeSpec(body))
    await board.call('POST', '/api/sessions', { sourceId: 'idea-00001-x', targetType: 'spec' })
    await vi.waitFor(() => expect(board.board.sessions.current()!.status).toBe('exited'), SESSION_WAIT)
    await board.board.sessions.whenFinished()
    return board
  }

  // spec-00001-AC-41.3 — reported on refresh, and the commit happens all the same
  it('reports a drifted declaration and commits the document anyway', async () => {
    const { call, repoRoot } = await advanceInto(DRIFTED)

    const { body } = await call('GET', '/api/graph')
    // The declaration lost its shape, so the criterion attributed to it has
    // lost its owner too — one drift, both readings reported.
    expect(body.diagnostics).toMatchObject([
      { docId: 'spec-00001-new', kind: 'item-shape', declaredId: 'spec-00001-FR-1' },
      { docId: 'spec-00001-new', kind: 'unattributable', declaredId: 'spec-00001-AC-1.1' },
    ])
    expect(lastCommitMessage(repoRoot)).toBe('wb(advance): spec-00001-new')
    expect(commitCount(repoRoot)).toBe(2)
  })

  // spec-00001-AC-41.4
  it('adds no diagnostic and leaves the node sound when the body follows the grammar', async () => {
    const { call } = await advanceInto(WELL_FORMED)

    const { body } = await call('GET', '/api/graph')
    expect(body.diagnostics).toEqual([])
    expect(body.nodes.find((n: { id: string }) => n.id === 'spec-00001-new').ok).toBe(true)
  })
})

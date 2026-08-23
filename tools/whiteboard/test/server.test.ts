import type { Server } from 'node:http'
import { appendFileSync, chmodSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { taskInstruction } from '../src/advance.ts'
import { Board } from '../src/server.ts'
import { ptySpawner, spawnPty } from '../src/pty.ts'
import type { SessionInfo, SessionListing, SpawnPty } from '../src/sessionManager.ts'
import { clarifyStatePath } from '../src/sessionTasks.ts'
import {
  SESSION_WAIT,
  armWatch,
  commitCount,
  doc,
  git,
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

/**
 * What a line-reading stand-in prints once the advance instruction has been
 * *submitted*: its last line stays in the terminal's line buffer until the Enter
 * that follows the CLI's first output arrives (issue-00011).
 */
const SUBMITTED_TAIL = `got:${taskInstruction(
  {
    targetType: 'prd',
    number: 1,
    idPrefix: 'prd-00001-',
    carry: 'parent',
    sourceId: 'idea-00001-x',
  },
  'idea/idea-00001-x.md',
)
  .split('\n')
  .at(-1)}`

/**
 * The silence the tests about waiting on the user run at (spec-00003-FR-6): the
 * real threshold is ten seconds, and nothing here is proved by waiting it out —
 * only that the flip happens with no output arriving at all. Comfortably longer
 * than the refresh window a flip is announced through, so a board reading the
 * listing after a flip reads what that flip left rather than the next one.
 */
const AWAIT_THRESHOLD = 500

const DRAFT_IDEA = doc({ id: 'idea-00001-x', type: 'idea', status: 'draft' }, '# Idea X\n')
const ACTIVE_IDEA = doc({ id: 'idea-00001-x', type: 'idea', status: 'active' }, '# Idea X\n')
const servers: Server[] = []
const watching: Board[] = []

/** Start a board on an ephemeral port and give back a fetch bound to it. */
function boardOn(
  files: Record<string, string>,
  agentArgs = ['-e', ''],
  command?: string,
  spawn = spawnPty,
  awaitThresholdMs?: number,
) {
  const { repoRoot, docsDir } = makeRepo(files)
  const config = testConfig()
  config.agents[0] = { ...config.agents[0]!, args: agentArgs, ...(command ? { command } : {}) }
  return boardOnRepo(repoRoot, docsDir, config, spawn, awaitThresholdMs)
}

/**
 * A board on a repo that is already there. Called a second time on the same
 * tree it is a restart, as far as anything on disk is concerned
 * (spec-00001-AC-54.2).
 */
function boardOnRepo(
  repoRoot: string,
  docsDir: string,
  config = testConfig(),
  spawn = spawnPty,
  // The silence a session is read as waiting after (spec-00003-FR-6); given only
  // by the tests that turn on it, so nothing else waits out the real threshold.
  awaitThresholdMs?: number,
) {
  const board = new Board({ repoRoot, docsDir, config, spawn, awaitThresholdMs })
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

/**
 * Stand-in agents whose output and exit the test fires, in start order. What the
 * commit queue does depends on the order two wrap-ups reach it in
 * (spec-00003-FR-8), and whether a session is silent depends on when it last
 * spoke (spec-00003-FR-6) — a spawned process speaks and ends when it does, so
 * both are things these tests hold in their own hands. A stand-in also echoes
 * nothing back, which a real pty does: the silence here is exactly the silence
 * the test scripted.
 */
function scriptedAgents() {
  const exits: Array<(exitCode: number) => void> = []
  const says: Array<(data: string) => void> = []
  const spawn: SpawnPty = () => {
    const listeners: Array<(event: { exitCode: number }) => void> = []
    const readers: Array<(data: string) => void> = []
    exits.push((exitCode) => {
      for (const listener of listeners) listener({ exitCode })
    })
    says.push((data) => {
      for (const reader of readers) reader(data)
    })
    return {
      onData: (listener) => void readers.push(listener),
      onExit: (listener) => void listeners.push(listener),
      write: () => {},
      resize: () => {},
      kill: () => {},
    }
  }
  return {
    spawn,
    exit: (index: number, exitCode = 0) => exits[index]!(exitCode),
    say: (index: number, data: string) => says[index]!(data),
  }
}

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
    // The entry list rides along with the config it belongs to (spec-00001-FR-53)
    expect(body.entry).toEqual(['idea', 'prd'])
  })

  /**
   * spec-00001-AC-56.1 — the two type sets the code holds (rule-00001-BR-20 and
   * BR-23) are part of the effective config, so the front end reads its entry
   * rulings off this payload instead of keeping a second copy of the rule.
   */
  it('serves the built-in clarifiable and auditable type sets', async () => {
    const { call } = boardOn({})

    const { body } = await call('GET', '/api/config')

    expect(body.clarifiable).toEqual(['idea', 'prd', 'spec', 'rule', 'design'])
    expect(body.auditable).toEqual(['spec', 'rule', 'design'])
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

  /**
   * spec-00002-AC-9.2 at the HTTP boundary. 409 rather than 422 is settled in
   * design-00001 §2: the request collides with the state of the repo — this id
   * points at no one document — and 422 stays the workflow's own refusal.
   */
  it('answers 409 for an id two documents declare, naming the files to fix', async () => {
    const { call } = boardOn({
      'idea/first.md': doc({ id: 'idea-00001-x', type: 'idea', status: 'draft' }, '# First\n'),
      'idea/second.md': doc({ id: 'idea-00001-x', type: 'idea', status: 'draft' }, '# Second\n'),
    })

    const { status, body } = await call('POST', '/api/docs/idea-00001-x/status', { to: 'active' })

    expect(status).toBe(409)
    expect(body.error).toMatch(/idea\/first\.md and idea\/second\.md; fix the id collision first/)
  })

  /** The node key of such a document is its path, and Express hands `%2F` back whole. */
  it('serves a colliding document addressed by its encoded file path', async () => {
    const second = doc({ id: 'idea-00001-x', type: 'idea', status: 'draft' }, '# Second\n')
    const { call } = boardOn({
      'idea/first.md': doc({ id: 'idea-00001-x', type: 'idea', status: 'draft' }, '# First\n'),
      'idea/second.md': second,
    })

    const { status, body } = await call('GET', `/api/docs/${encodeURIComponent('idea/second.md')}`)

    expect(status).toBe(200)
    expect(body.content).toBe(second)
  })

  // spec-00001-AC-6.1
  it('serves the legal transitions', async () => {
    const { call } = boardOn({ 'idea/a.md': DRAFT_IDEA })
    expect((await call('GET', '/api/docs/idea-00001-x/transitions')).body).toEqual(['active', 'archived'])
  })

  // spec-00001-AC-6.5 — the revision round is a candidate on an active living doc
  // (rule-00001-BR-3 as amended, decision-00008 §2 第 1 条)
  it('offers draft as a transition of an active living doc', async () => {
    const { call } = boardOn({ 'idea/a.md': ACTIVE_IDEA })
    expect((await call('GET', '/api/docs/idea-00001-x/transitions')).body).toEqual(['draft', 'archived'])
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

  /**
   * The global coverage view's one read (spec-00002-FR-10, design-00001 §7):
   * every spec and rule in the repo, whatever its status, with the three counts
   * and the items behind them.
   */
  describe('GET /api/coverage', () => {
    // spec-00002-AC-10.1
    it('serves a row per spec and rule, each with its counts and items', async () => {
      const { call } = boardOn({
        'spec/a.md': SPEC,
        'record/r.md': record('active', ['| spec-00001-AC-1.1 | draws a node | pass |']),
      })

      const { status, body } = await call('GET', '/api/coverage')

      expect(status).toBe(200)
      expect(body).toEqual([
        {
          docId: 'spec-00001-x',
          title: 'Spec',
          verified: 1,
          failing: 0,
          uncovered: 1,
          items: [
            { id: 'spec-00001-FR-1', coverage: 'verified' },
            { id: 'spec-00001-FR-2', coverage: 'uncovered' },
          ],
        },
      ])
    })

    // spec-00002-AC-10.3
    it('serves an empty list for a repo with no spec and no rule', async () => {
      expect((await boardOn({ 'idea/a.md': ACTIVE_IDEA }).call('GET', '/api/coverage')).body).toEqual([])
    })
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

  // spec-00001-AC-7.1, and design-00001 §7: a refusal that is not the gate's names no gaps
  it('answers 422 for an illegal transition', async () => {
    const { call } = boardOn({ 'idea/a.md': DRAFT_IDEA })
    const { status, body } = await call('POST', '/api/docs/idea-00001-x/status', { to: 'resolved' })

    expect(status).toBe(422)
    expect(body.error).toMatch(/not a legal transition/)
    expect(body.gaps).toBeUndefined()
  })

  /**
   * The resolved gate over the wire (spec-00001-FR-52): the refusal is a 422 like
   * any other, told apart by the `gaps` it carries (design-00001 §7).
   */
  describe('the resolved gate', () => {
    const SPEC = doc(
      { id: 'spec-00001-b', type: 'spec', status: 'active' },
      [
        '# Spec',
        '',
        '- **spec-00001-FR-1** (Event) the system shall do the thing',
        '',
        '- **spec-00001-AC-1.1** (spec-00001-FR-1)',
        '  Given a board',
        '  When it loads',
        '  Then it works',
        '',
      ].join('\n'),
    )
    const PLAN = doc({ id: 'plan-00001-y', type: 'plan', status: 'open', implements: '[spec-00001-FR-1]' }, '# Plan\n')
    const RECORD = doc(
      { id: 'record-00001-r', type: 'record', status: 'active', parent: 'plan-00001-y' },
      ['# 验收记录', '', '| GWT id | 测试 | 结果 |', '| --- | --- | --- |', '| spec-00001-AC-1.1 | t.ts | pass |', ''].join(
        '\n',
      ),
    )

    // spec-00001-AC-52.2 over HTTP
    it('answers 422 naming the gaps, and leaves the file alone', async () => {
      const { call, docsDir } = boardOn({ 'spec/b.md': SPEC, 'plan/a.md': PLAN })

      const { status, body } = await call('POST', '/api/docs/plan-00001-y/status', { to: 'resolved' })

      expect(status).toBe(422)
      expect(body.gaps).toEqual(['spec-00001-FR-1'])
      expect(body.error).toMatch(/spec-00001-FR-1/)
      expect(readFileSync(join(docsDir, 'plan/a.md'), 'utf8')).toBe(PLAN)
    })

    // spec-00001-AC-52.1 over HTTP
    it('applies the transition once the record naming the plan verifies its scope', async () => {
      const { call } = boardOn({ 'spec/b.md': SPEC, 'plan/a.md': PLAN, 'record/r.md': RECORD })

      const { status, body } = await call('POST', '/api/docs/plan-00001-y/status', { to: 'resolved' })

      expect(status).toBe(200)
      expect(body).toEqual({ committed: true, status: 'resolved' })
    })
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
  /** A prd related to the idea by `parent`, so an edge joins the two documents. */
  const RELATED_PRD = doc({ id: 'prd-00001-p', type: 'prd', status: 'active', parent: 'idea-00001-x' }, '# Prd P\n')
  const HOLD = ['-e', 'setTimeout(() => {}, 5000)']

  /** A board whose flow config declares the session cap this test needs (spec-00003-FR-3). */
  function cappedBoard(files: Record<string, string>, maxSessions: number, agentArgs = HOLD) {
    const { repoRoot, docsDir } = makeRepo(files)
    const config = testConfig(`max_sessions: ${maxSessions}\n`)
    config.agents[0] = { ...config.agents[0]!, args: agentArgs }
    return boardOnRepo(repoRoot, docsDir, config)
  }

  it('lists no session before the first one is started', async () => {
    const { call } = boardOn({})
    expect((await call('GET', '/api/sessions')).body).toEqual({ sessions: [] })
  })

  // spec-00001-AC-11.1
  it('starts an advance the flow config allows', async () => {
    const { call, board } = boardOn({ 'idea/a.md': ACTIVE_IDEA })

    const { status, body } = await call('POST', '/api/sessions', { sourceId: 'idea-00001-x', targetType: 'prd' })

    expect(status).toBe(200)
    expect(body.targetType).toBe('prd')
    await vi.waitFor(() => expect(board.sessions.latest()!.status).toBe('exited'), SESSION_WAIT)
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

  // spec-00001-AC-18.1 — the document already has a session, whatever kind is asked for
  it('answers 409 with the same-document reason while that document has a session', async () => {
    const { call } = boardOn({ 'idea/a.md': ACTIVE_IDEA }, HOLD)
    await call('POST', '/api/sessions', { sourceId: 'idea-00001-x', targetType: 'prd' })

    const { status, body } = await call('POST', '/api/sessions', { sourceId: 'idea-00001-x', targetType: 'prd' })

    expect(status).toBe(409)
    expect(body.reason).toBe('doc-busy')
    expect(body.error).toMatch(/already has a running agent session/)
  })

  // spec-00001-AC-18.3 — the refusal is idempotent: no session, no commit
  it('answers 409 again for the same document, with no side effect', async () => {
    const { call, repoRoot } = boardOn({ 'idea/a.md': ACTIVE_IDEA }, HOLD)
    await call('POST', '/api/sessions', { sourceId: 'idea-00001-x', targetType: 'prd' })
    const commits = commitCount(repoRoot)
    await call('POST', '/api/sessions', { sourceId: 'idea-00001-x', targetType: 'prd' })

    expect((await call('POST', '/api/sessions', { sourceId: 'idea-00001-x', targetType: 'prd' })).status).toBe(409)

    expect((await call('GET', '/api/sessions')).body.sessions).toHaveLength(1)
    expect(commitCount(repoRoot)).toBe(commits)
  })

  // spec-00003-AC-2.3 — the exclusion is the document's own; it does not travel
  // along the edges to its related documents
  it('starts a session on a document related to the one that has a session', async () => {
    const { call } = boardOn({ 'idea/a.md': ACTIVE_IDEA, 'prd/p.md': RELATED_PRD }, HOLD)
    await call('POST', '/api/sessions/ask', { docId: 'idea-00001-x' })

    const { status, body } = await call('POST', '/api/sessions/ask', { docId: 'prd-00001-p' })

    expect(status).toBe(200)
    expect(body.status).toBe('running')
    expect((await call('GET', '/api/sessions')).body.sessions.map((s: SessionInfo) => s.status)).toEqual([
      'running',
      'running',
    ])
  })

  // spec-00001-AC-18.2 — nothing to do with the target document: the cap is full
  it('answers 409 with the cap reason once the cap is reached', async () => {
    const { call } = cappedBoard({ 'idea/a.md': ACTIVE_IDEA, 'prd/p.md': RELATED_PRD }, 1)
    await call('POST', '/api/sessions/ask', { docId: 'idea-00001-x' })

    const { status, body } = await call('POST', '/api/sessions/ask', { docId: 'prd-00001-p' })

    expect(status).toBe(409)
    expect(body.reason).toBe('cap-reached')
    expect(body.error).toMatch(/max_sessions/)
    expect((await call('GET', '/api/sessions')).body.sessions).toHaveLength(1)
  })

  // spec-00003-AC-3.3 — a slot freed by an ending session is a slot to start in
  it('starts a session at the cap once one of the running ones has ended', async () => {
    const { call, board } = cappedBoard({ 'idea/a.md': ACTIVE_IDEA, 'prd/p.md': RELATED_PRD }, 1, ['-e', ''])
    await call('POST', '/api/sessions/ask', { docId: 'idea-00001-x' })
    await vi.waitFor(() => expect(board.sessions.latest()!.status).toBe('exited'), SESSION_WAIT)
    await board.sessions.whenFinished()

    expect((await call('POST', '/api/sessions/ask', { docId: 'prd-00001-p' })).status).toBe(200)
  })

  /**
   * spec-00003-AC-1.3 — the number of an advance that is still running counts as
   * taken, so the second advance is told a different one even though the first
   * document is not on disk yet (design-00001 §5). The instruction the agent was
   * handed is where the allocated id shows.
   */
  it('gives two parallel advances of the same type different target ids', async () => {
    const { repoRoot, docsDir } = makeRepo({ 'idea/a.md': ACTIVE_IDEA, 'prd/p.md': RELATED_PRD })
    const instructions: string[] = []
    const config = testConfig()
    const board = new Board({
      repoRoot,
      docsDir,
      config,
      spawn: () => ({
        onData: () => {},
        onExit: () => {},
        write: (data: string) => void instructions.push(data),
        resize: () => {},
        kill: () => {},
      }),
    })
    const server = board.listen(0)
    servers.push(server)
    watching.push(board)
    const port = (server.address() as { port: number }).port
    const advance = (sourceId: string) =>
      fetch(`http://127.0.0.1:${port}/api/sessions`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ sourceId, targetType: 'spec' }),
      })

    await advance('idea-00001-x')
    await advance('prd-00001-p')

    expect(instructions[0]).toContain('spec-00001-<slug>')
    expect(instructions[1]).toContain('spec-00002-<slug>')
  })

  // spec-00003-AC-9.1's server half — the sessions outlive the browser, and a
  // board opening again finds every one of them (spec-00001-AC-21.2)
  it('lists every running session so a reconnecting board can find them all', async () => {
    const { call } = boardOn({ 'idea/a.md': ACTIVE_IDEA, 'prd/p.md': RELATED_PRD }, HOLD)
    await call('POST', '/api/sessions', { sourceId: 'idea-00001-x', targetType: 'prd' })
    await call('POST', '/api/sessions/ask', { docId: 'prd-00001-p' })

    const { body } = await call('GET', '/api/sessions')

    expect(body.sessions).toHaveLength(2)
    expect(body.sessions.map((session: SessionInfo) => session.sourceId)).toEqual(['idea-00001-x', 'prd-00001-p'])
    expect(body.sessions.every((session: SessionInfo) => session.status === 'running')).toBe(true)
    // The panel lists each session's kind and when it started (spec-00003-FR-4).
    expect(body.sessions[0].kind).toBe('advance')
    expect(body.sessions[0].startedAt).toMatch(/^\d{4}-\d{2}-\d{2}T/)
  })

  /**
   * spec-00003-AC-6.5's server half — two sessions silent past the threshold are
   * both marked in the one payload the panel reads (design-00001 §7). The count
   * the top bar's badge shows is the front end's reading of these rows
   * (spec-00003-FR-6, T7), so what is checked here is that both rows carry it.
   */
  it('marks every session that has gone quiet in the listing', async () => {
    const agents = scriptedAgents()
    const { call } = boardOn(
      { 'idea/a.md': ACTIVE_IDEA, 'prd/p.md': RELATED_PRD },
      undefined,
      undefined,
      agents.spawn,
      AWAIT_THRESHOLD,
    )
    await call('POST', '/api/sessions/ask', { docId: 'idea-00001-x' })
    await call('POST', '/api/sessions/ask', { docId: 'prd-00001-p' })

    await vi.waitFor(async () => {
      const { body } = await call('GET', '/api/sessions')
      expect(body.sessions.map((session: SessionListing) => session.awaiting)).toEqual([true, true])
    }, SESSION_WAIT)
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
    expect(board.sessions.latest()!.sourceId).toBe('spec-00001-b')
  })

  // spec-00001-AC-9.2
  it('answers 422 and starts nothing for a document that is not draft', async () => {
    const { call, board } = boardOn({ 'idea/a.md': ACTIVE_IDEA })

    const { status, body } = await call('POST', '/api/sessions/clarify', { docId: 'idea-00001-x' })

    expect(status).toBe(422)
    expect(body.error).toMatch(/applies to a draft/)
    expect(board.sessions.latest()).toBeNull()
  })

  // spec-00001-AC-9.4
  it('answers 422 and starts nothing for a draft of a type that is not clarifiable', async () => {
    const { call, board } = boardOn({
      'record/r.md': doc({ id: 'record-00001-r', type: 'record', status: 'draft' }, '# Record\n'),
    })

    const { status, body } = await call('POST', '/api/sessions/clarify', { docId: 'record-00001-r' })

    expect(status).toBe(422)
    expect(body.error).toMatch(/does not apply to a record/)
    expect(board.sessions.latest()).toBeNull()
  })

  // spec-00001-AC-47.1 — ask is not a review action: any type, any status
  it('starts an ask session on an active record', async () => {
    const { call, board } = boardOn({ 'record/r.md': ACTIVE_RECORD }, HOLD)

    const { status, body } = await call('POST', '/api/sessions/ask', { docId: 'record-00001-r' })

    expect(status).toBe(200)
    expect(body.kind).toBe('ask')
    expect(board.sessions.latest()!.status).toBe('running')
  })

  // spec-00001-AC-47.5
  it('answers 422 and starts nothing for an anomalous document', async () => {
    const { call, board } = boardOn({ 'spec/broken.md': BROKEN })

    const { status, body } = await call('POST', '/api/sessions/ask', { docId: 'nope' })

    expect(status).toBe(422)
    expect(body.error).toMatch(/front matter problems/)
    expect(board.sessions.latest()).toBeNull()
  })

  // spec-00001-AC-19.2
  it('answers 409 and starts nothing when the target document was deleted', async () => {
    const { call, board, docsDir } = boardOn({ 'spec/b.md': DRAFT_SPEC })
    rmSync(join(docsDir, 'spec/b.md'))

    for (const path of ['/api/sessions/ask', '/api/sessions/clarify']) {
      const { status, body } = await call('POST', path, { docId: 'spec-00001-b' })

      expect(status).toBe(409)
      expect(body.error).toMatch(/refresh the board/)
      // The third reason a start is refused (design-00001 §7), told apart from
      // the two concurrency ones.
      expect(body.reason).toBe('doc-missing')
    }
    expect(board.sessions.latest()).toBeNull()
  })

  // spec-00003-AC-2.1 — the exclusion holds across kinds, on the one document
  it('answers 409 for an ask on the document a clarify session is running on', async () => {
    const { call, board } = boardOn({ 'spec/b.md': DRAFT_SPEC, 'record/r.md': ACTIVE_RECORD }, HOLD)
    await call('POST', '/api/sessions/clarify', { docId: 'spec-00001-b' })

    const { status, body } = await call('POST', '/api/sessions/ask', { docId: 'spec-00001-b' })

    expect(status).toBe(409)
    expect(body.reason).toBe('doc-busy')
    expect(board.sessions.latest()).toMatchObject({ kind: 'clarify', sourceId: 'spec-00001-b', status: 'running' })
  })

  // spec-00003-AC-1.1 — two documents, two kinds, both running
  it('starts an ask on another document while a clarify session runs', async () => {
    const { call, board } = boardOn({ 'spec/b.md': DRAFT_SPEC, 'record/r.md': ACTIVE_RECORD }, HOLD)
    await call('POST', '/api/sessions/clarify', { docId: 'spec-00001-b' })

    const { status, body } = await call('POST', '/api/sessions/ask', { docId: 'record-00001-r' })

    expect(status).toBe(200)
    expect(body.status).toBe('running')
    expect(board.sessions.list().map((session) => [session.kind, session.status])).toEqual([
      ['clarify', 'running'],
      ['ask', 'running'],
    ])
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
    expect(board.sessions.attach(board.sessions.latest()!.id, () => {}).buffer).toContain('could not start the agent')
    expect(commitCount(repoRoot)).toBe(commits)
    expect(existsSync(join(repoRoot, clarifyStatePath('spec-00001-b')))).toBe(false)
  })

  // spec-00001-AC-14.8, and AC-46.4 for what stays out of that commit
  it('commits what a clarify session wrote under docs, and nothing outside it', async () => {
    const { call, board, repoRoot } = boardOn({ 'spec/b.md': DRAFT_SPEC }, REVISE('spec/b.md'))
    mkdirSync(join(repoRoot, '.whiteboard/clarify'), { recursive: true })
    writeFileSync(join(repoRoot, clarifyStatePath('spec-00001-b')), '{"answered":1}')

    await call('POST', '/api/sessions/clarify', { docId: 'spec-00001-b' })
    await vi.waitFor(() => expect(board.sessions.latest()!.status).toBe('exited'), SESSION_WAIT)
    await board.sessions.whenFinished()

    expect(lastCommitMessage(repoRoot)).toBe('wb(clarify): spec-00001-b')
    expect(lastCommitFiles(repoRoot)).toEqual(['docs/spec/b.md'])
  })

  // spec-00001-AC-14.7
  it('commits what an ask session wrote under docs', async () => {
    const { call, board, repoRoot } = boardOn({ 'record/r.md': ACTIVE_RECORD }, REVISE('record/r.md'))

    await call('POST', '/api/sessions/ask', { docId: 'record-00001-r' })
    await vi.waitFor(() => expect(board.sessions.latest()!.status).toBe('exited'), SESSION_WAIT)
    await board.sessions.whenFinished()

    expect(lastCommitMessage(repoRoot)).toBe('wb(ask): record-00001-r')
  })

  // spec-00001-AC-47.2 — a discussion that concluded nothing changes nothing
  it('leaves the document and the history alone when an ask session wrote nothing', async () => {
    const { call, board, repoRoot, docsDir } = boardOn({ 'spec/b.md': DRAFT_SPEC })
    const commits = commitCount(repoRoot)

    await call('POST', '/api/sessions/ask', { docId: 'spec-00001-b' })
    await vi.waitFor(() => expect(board.sessions.latest()!.status).toBe('exited'), SESSION_WAIT)
    await board.sessions.whenFinished()

    expect(readFileSync(join(docsDir, 'spec/b.md'), 'utf8')).toBe(DRAFT_SPEC)
    expect(commitCount(repoRoot)).toBe(commits)
    expect(board.sessions.latest()!.outcome!.committed).toBe(false)
  })
})

/**
 * The fourth session kind (spec-00001-FR-50 and FR-51): the same channel, the
 * same one slot, its own ruling. Nothing here writes the document — the session's
 * agent does, and the board commits what it left.
 */
describe('audit sessions', () => {
  const DRAFT_SPEC = doc({ id: 'spec-00001-b', type: 'spec', status: 'draft' }, '# Spec\n')
  const DRAFT_DESIGN = doc({ id: 'design-00001-d', type: 'design', status: 'draft' }, '# Design\n')
  const HOLD = ['-e', 'setTimeout(() => {}, 5000)']

  // spec-00001-AC-50.1
  it('starts an audit session on a draft spec and streams its output to the terminal', async () => {
    const { call, board } = boardOn({ 'spec/b.md': DRAFT_SPEC }, ['-e', 'console.log("auditing"); setTimeout(() => {}, 5000)'])

    const { status, body } = await call('POST', '/api/sessions/audit', { docId: 'spec-00001-b' })

    expect(status).toBe(200)
    expect(body.kind).toBe('audit')
    expect(body.status).toBe('running')
    expect(board.sessions.latest()!.sourceId).toBe('spec-00001-b')
    await vi.waitFor(() => expect(board.sessions.attach(board.sessions.latest()!.id, () => {}).buffer).toContain('auditing'), SESSION_WAIT)
  })

  // spec-00001-AC-50.3
  it('commits what an audit session wrote under docs, naming the action and the document', async () => {
    const { call, board, repoRoot } = boardOn({ 'design/d.md': DRAFT_DESIGN }, [
      '-e',
      `require('fs').appendFileSync('design/d.md', '\\n## Open Questions\\n\\n- which failure mode is unstated?\\n')`,
    ])

    await call('POST', '/api/sessions/audit', { docId: 'design-00001-d' })
    await vi.waitFor(() => expect(board.sessions.latest()!.status).toBe('exited'), SESSION_WAIT)
    await board.sessions.whenFinished()

    expect(lastCommitMessage(repoRoot)).toBe('wb(audit): design-00001-d')
    expect(lastCommitFiles(repoRoot)).toEqual(['docs/design/d.md'])
  })

  // spec-00001-AC-50.4 — an audit that found nothing to write leaves no trace
  it('leaves the document and the history alone when an audit session wrote nothing', async () => {
    const { call, board, repoRoot, docsDir } = boardOn({ 'spec/b.md': DRAFT_SPEC })
    const commits = commitCount(repoRoot)

    await call('POST', '/api/sessions/audit', { docId: 'spec-00001-b' })
    await vi.waitFor(() => expect(board.sessions.latest()!.status).toBe('exited'), SESSION_WAIT)
    await board.sessions.whenFinished()

    expect(readFileSync(join(docsDir, 'spec/b.md'), 'utf8')).toBe(DRAFT_SPEC)
    expect(commitCount(repoRoot)).toBe(commits)
    expect(board.sessions.latest()!.outcome!.committed).toBe(false)
  })

  // spec-00001-AC-51.1
  it('answers 422 and starts nothing for a draft of a type that is not auditable', async () => {
    const { call, board } = boardOn({ 'idea/a.md': DRAFT_IDEA })

    const { status, body } = await call('POST', '/api/sessions/audit', { docId: 'idea-00001-x' })

    expect(status).toBe(422)
    expect(body.error).toMatch(/does not apply to an? idea/)
    expect(board.sessions.latest()).toBeNull()
  })

  // spec-00001-AC-51.2 — the entry is not offered, and the request is refused all the same
  it('answers 422 and starts nothing for an auditable type that is no longer draft', async () => {
    const { call, board } = boardOn({ 'spec/b.md': doc({ id: 'spec-00001-b', type: 'spec', status: 'active' }) })

    const { status, body } = await call('POST', '/api/sessions/audit', { docId: 'spec-00001-b' })

    expect(status).toBe(422)
    expect(body.error).toMatch(/applies to a draft/)
    expect(board.sessions.latest()).toBeNull()
  })

  // spec-00001-AC-51.3
  it('answers 422 and starts nothing for an anomalous document', async () => {
    const { call, board } = boardOn({ 'spec/broken.md': doc({ id: 'nope', type: 'spec', status: 'draft' }) })

    const { status, body } = await call('POST', '/api/sessions/audit', { docId: 'nope' })

    expect(status).toBe(422)
    expect(body.error).toMatch(/front matter problems/)
    expect(board.sessions.latest()).toBeNull()
  })

  // spec-00001-AC-19.2 for audit's half of «the target is gone»
  it('answers 409 and starts nothing when the target document was deleted', async () => {
    const { call, board, docsDir } = boardOn({ 'spec/b.md': DRAFT_SPEC })
    rmSync(join(docsDir, 'spec/b.md'))

    const { status, body } = await call('POST', '/api/sessions/audit', { docId: 'spec-00001-b' })

    expect(status).toBe(409)
    expect(body.error).toMatch(/refresh the board/)
    expect(board.sessions.latest()).toBeNull()
  })

  it('answers 422 when the request names no document', async () => {
    const { call } = boardOn({ 'spec/b.md': DRAFT_SPEC })
    expect((await call('POST', '/api/sessions/audit', {})).status).toBe(422)
  })

  // spec-00003-AC-2.1 — the fourth kind is no exception to the exclusion
  it('answers 409 for an audit while a clarify session is running on that document', async () => {
    const { call, board } = boardOn({ 'spec/b.md': DRAFT_SPEC }, HOLD)
    await call('POST', '/api/sessions/clarify', { docId: 'spec-00001-b' })

    const { status, body } = await call('POST', '/api/sessions/audit', { docId: 'spec-00001-b' })

    expect(status).toBe(409)
    expect(body.reason).toBe('doc-busy')
    expect(board.sessions.latest()).toMatchObject({ kind: 'clarify', sourceId: 'spec-00001-b', status: 'running' })
  })

  // spec-00003-AC-2.1 the other way round: the audit excludes the rest on its document
  it('answers 409 for a clarify, an ask and an advance while an audit session is running', async () => {
    const { call, board } = boardOn({ 'spec/b.md': DRAFT_SPEC }, HOLD)
    await call('POST', '/api/sessions/audit', { docId: 'spec-00001-b' })

    for (const [path, request] of [
      ['/api/sessions/clarify', { docId: 'spec-00001-b' }],
      ['/api/sessions/ask', { docId: 'spec-00001-b' }],
      ['/api/sessions', { sourceId: 'spec-00001-b', targetType: 'plan' }],
    ] as const) {
      expect((await call('POST', path, request)).status).toBe(409)
    }
    expect(board.sessions.latest()).toMatchObject({ kind: 'audit', status: 'running' })
  })
})

/**
 * spec-00001-FR-49 (issue-00010): the one way out of a session that will not end
 * by itself. The exit wrap-up is the ordinary one — end state, the kind's commit,
 * a refreshed board — so what is new here is only the way in. The session is
 * named in the path, and the refusal is judged for that session alone
 * (spec-00003-FR-5, design-00001 §7).
 */
describe('DELETE /api/sessions/:id', () => {
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

  /** A clarify session on the draft spec, held until it has actually written; its id. */
  async function clarifyThatWrote(call: BoardCall, docsDir: string): Promise<string> {
    const { body } = await call('POST', '/api/sessions/clarify', { docId: 'spec-00001-b' })
    await vi.waitFor(
      () => expect(readFileSync(join(docsDir, 'spec/b.md'), 'utf8')).toContain('revised'),
      SESSION_WAIT,
    )
    return body.id
  }

  // spec-00001-AC-49.1
  it('ends the running process and leaves the end state in the terminal', async () => {
    const { call, board } = boardOn({ 'idea/a.md': ACTIVE_IDEA }, HOLD)
    const { body: started } = await call('POST', '/api/sessions', { sourceId: 'idea-00001-x', targetType: 'prd' })

    const { status, body } = await call('DELETE', `/api/sessions/${started.id}`)

    expect(status).toBe(200)
    // A stopped session ends `terminated`, which is what tells the panel and the
    // history that the user ended it (design-00001 §5, spec-00003-FR-4).
    expect(body.status).toBe('terminated')
    expect(board.sessions.latest()!.status).toBe('terminated')
    expect(board.sessions.attach(board.sessions.latest()!.id, () => {}).buffer).toContain('session ended with code')
  })

  // spec-00003-AC-5.3 — the stop reaches the session it names, and no other
  it('stops the session it names and leaves the other one running', async () => {
    const { call, board } = boardOn({ 'idea/a.md': ACTIVE_IDEA, 'spec/b.md': DRAFT_SPEC }, HOLD)
    const { body: first } = await call('POST', '/api/sessions', { sourceId: 'idea-00001-x', targetType: 'prd' })
    await call('POST', '/api/sessions/clarify', { docId: 'spec-00001-b' })

    expect((await call('DELETE', `/api/sessions/${first.id}`)).status).toBe(200)

    expect(board.sessions.list().map((session) => session.status)).toEqual(['terminated', 'running'])
  })

  // spec-00001-AC-49.2 — a stopped session's writings are committed under its kind
  it('commits what the stopped session wrote, named by its kind', async () => {
    const { call, repoRoot, docsDir } = boardOn({ 'spec/b.md': DRAFT_SPEC }, REVISE_AND_HOLD)
    const id = await clarifyThatWrote(call, docsDir)

    await call('DELETE', `/api/sessions/${id}`)

    expect(lastCommitMessage(repoRoot)).toBe('wb(clarify): spec-00001-b')
    expect(lastCommitFiles(repoRoot)).toEqual(['docs/spec/b.md'])
  })

  // spec-00001-AC-49.3 — the document is free again, which is the point of stopping
  it('lets a new session start on that document once the stuck one has been stopped', async () => {
    const { call } = boardOn({ 'idea/a.md': ACTIVE_IDEA }, HOLD)
    const { body: started } = await call('POST', '/api/sessions', { sourceId: 'idea-00001-x', targetType: 'prd' })

    await call('DELETE', `/api/sessions/${started.id}`)

    expect((await call('POST', '/api/sessions', { sourceId: 'idea-00001-x', targetType: 'prd' })).status).toBe(200)
  })

  // spec-00001-AC-49.6 — stopping is not an action that can be taken twice: the
  // second attempt must not put a second commit on the same wrap-up.
  it('refuses a second stop of the same session and commits nothing again', async () => {
    const { call, repoRoot, docsDir } = boardOn({ 'spec/b.md': DRAFT_SPEC }, REVISE_AND_HOLD)
    const id = await clarifyThatWrote(call, docsDir)
    await call('DELETE', `/api/sessions/${id}`)
    const commits = commitCount(repoRoot)

    const { status, body } = await call('DELETE', `/api/sessions/${id}`)

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
    const id = await clarifyThatWrote(call, docsDir)

    await call('DELETE', `/api/sessions/${id}`)

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
    const { body: started } = await call('POST', '/api/sessions', { sourceId: 'idea-00001-x', targetType: 'prd' })
    await vi.waitFor(() => expect(existsSync(PID_FILE)).toBe(true), SESSION_WAIT)
    const pid = Number(readFileSync(PID_FILE, 'utf8').trim())
    expect(isRunning(pid)).toBe(true)

    const { status, body } = await call('DELETE', `/api/sessions/${started.id}`)

    expect(status).toBe(200)
    expect(body.status).toBe('terminated')
    expect(board.sessions.attach(board.sessions.latest()!.id, () => {}).buffer).toContain('session ended with code')
    expect(isRunning(pid)).toBe(false)
    rmSync(PID_FILE, { force: true })
  })

  // spec-00001-AC-49.4 — an id the registry never knew is nothing to stop
  it('answers 404 for a session id it does not know', async () => {
    const { call } = boardOn({})

    const { status, body } = await call('DELETE', '/api/sessions/no-such-session')

    expect(status).toBe(404)
    expect(body.error).toMatch(/no running agent session/)
  })

  /**
   * spec-00001-AC-49.4 and spec-00003-AC-5.5 — a session that already ended is
   * not one to stop either, and the answer is that session's alone: another one
   * running does not make the ended one stoppable.
   */
  it('answers 404 for a session that has already ended, whatever else runs', async () => {
    const { call, board } = boardOn({ 'idea/a.md': ACTIVE_IDEA, 'spec/b.md': DRAFT_SPEC }, ['-e', ''])
    const { body: ended } = await call('POST', '/api/sessions', { sourceId: 'idea-00001-x', targetType: 'prd' })
    await vi.waitFor(() => expect(board.sessions.latest()!.status).toBe('exited'), SESSION_WAIT)
    await board.sessions.whenFinished()
    const { body: running } = await call('POST', '/api/sessions/clarify', { docId: 'spec-00001-b' })

    expect((await call('DELETE', `/api/sessions/${ended.id}`)).status).toBe(404)

    expect(board.sessions.list().find((session) => session.id === running.id)!.status).toBe('running')
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
    const session = board.sessions.start({ kind: 'ask', sourceId: 'idea-00001-x', instruction: 'answer this' })
    return { board, sizes, typed, session, port: (server.address() as { port: number }).port }
  }

  /**
   * An open terminal socket on that board's session. The session rides in the
   * query, so the frames reach that session's pty and no other
   * (design-00001 §7, spec-00003-FR-5).
   */
  async function attach(port: number, sessionId: string) {
    const socket = new WebSocket(`ws://127.0.0.1:${port}/api/terminal?sessionId=${sessionId}`)
    await new Promise<void>((resolve) => socket.addEventListener('open', () => resolve()))
    return socket
  }

  /** The size frame as the front end sends it: binary, holding the JSON pair. */
  function sizeFrame(cols: number, rows: number): Buffer {
    return Buffer.from(JSON.stringify({ cols, rows }))
  }

  // spec-00001-AC-12.5
  it('resizes the session pty to the size the attached terminal reports', async () => {
    const { sizes, port, session } = boardWithRecordingPty()
    const socket = await attach(port, session.id)

    socket.send(sizeFrame(100, 40))

    await vi.waitFor(() => expect(sizes).toEqual([{ cols: 100, rows: 40 }]))
    socket.close()
  })

  // spec-00001-AC-12.6 — the panel moved, so the size the pty holds moves with it
  it('resizes the pty again for every later size frame', async () => {
    const { sizes, port, session } = boardWithRecordingPty()
    const socket = await attach(port, session.id)

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
    const { sizes, typed, port, session } = boardWithRecordingPty()
    const socket = await attach(port, session.id)

    socket.send(sizeFrame(100, 40))
    socket.send('{"cols":9,"rows":9}')

    // The instruction is the session's own first write, and it carries no submit
    // byte: the Enter is a later press this silent stand-in never triggers, since
    // it prints nothing to say it is ready (issue-00011). The keystroke frame is
    // forwarded exactly as it arrived.
    await vi.waitFor(() => expect(typed).toEqual(['answer this', '{"cols":9,"rows":9}']))
    expect(sizes).toEqual([{ cols: 100, rows: 40 }])
    socket.close()
  })

  it('drops a control frame it cannot read as a size, and carries on', async () => {
    const { sizes, typed, port, session } = boardWithRecordingPty()
    const socket = await attach(port, session.id)

    socket.send(Buffer.from('not json at all'))
    socket.send(Buffer.from(JSON.stringify({ cols: 'wide', rows: null })))
    socket.send(sizeFrame(100, 40))

    await vi.waitFor(() => expect(sizes).toEqual([{ cols: 100, rows: 40 }]))
    expect(typed).toEqual(['answer this'])
    socket.close()
  })
})

describe('the terminal socket', () => {
  const DRAFT_SPEC = doc({ id: 'spec-00001-b', type: 'spec', status: 'draft' }, '# Spec\n')

  /** Collect the frames of one session's channel, then hand back the socket. */
  function connect(port: number, sessionId = '') {
    const socket = new WebSocket(`ws://127.0.0.1:${port}/api/terminal?sessionId=${sessionId}`)
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
    const { body: started } = await call('POST', '/api/sessions', { sourceId: 'idea-00001-x', targetType: 'prd' })

    const terminal = connect(port, started.id)
    await terminal.opened
    await vi.waitFor(() => expect(terminal.text).toContain('got:Write one new prd document'), SESSION_WAIT)
    // The line-reading stand-in completes the instruction's last line only once
    // the submit has landed; typing before that would lengthen that line rather
    // than start one of its own (issue-00011).
    await vi.waitFor(() => expect(terminal.text).toContain(SUBMITTED_TAIL), SESSION_WAIT)

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
    const { body: started } = await call('POST', '/api/sessions', { sourceId: 'idea-00001-x', targetType: 'prd' })

    const first = connect(port, started.id)
    await first.opened
    await vi.waitFor(() => expect(first.text).toContain('printed early'), SESSION_WAIT)
    first.socket.close()
    await first.closed

    const second = connect(port, started.id)
    await second.opened
    await vi.waitFor(() => expect(second.text).toContain('printed early'), SESSION_WAIT)
    second.socket.close()
  })

  /**
   * spec-00003-AC-9.1 — two sessions, two channels: each terminal replays the
   * output of the session it names and nothing of the other's
   * (spec-00003-AC-1.2, spec-00001-FR-21 over several sessions).
   */
  it('replays each session its own output to its own terminal', async () => {
    const { call, port } = boardOn({ 'idea/a.md': ACTIVE_IDEA, 'spec/b.md': DRAFT_SPEC }, [
      '-e',
      "process.stdin.on('data', (d) => console.log('got:' + d.toString().trim()))",
    ])
    const { body: advance } = await call('POST', '/api/sessions', { sourceId: 'idea-00001-x', targetType: 'prd' })
    const { body: clarify } = await call('POST', '/api/sessions/clarify', { docId: 'spec-00001-b' })

    const first = connect(port, advance.id)
    const second = connect(port, clarify.id)
    await Promise.all([first.opened, second.opened])

    await vi.waitFor(() => expect(first.text).toContain('got:Write one new prd document'), SESSION_WAIT)
    await vi.waitFor(() => expect(second.text).toContain('got:This is a clarify session'), SESSION_WAIT)
    expect(first.text).not.toContain('This is a clarify session')
    first.socket.close()
    second.socket.close()
  })

  it('closes a terminal opened on no session at all', async () => {
    const { port } = boardOn({})
    const terminal = connect(port)
    await terminal.closed
    expect(terminal.text).toBe('')
  })

  it('closes a terminal opened on a session id it does not know', async () => {
    const { port } = boardOn({})
    const terminal = connect(port, 'no-such-session')
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
  const DRAFT_SPEC = doc({ id: 'spec-00001-b', type: 'spec', status: 'draft' }, '# Spec\n')
  const ACTIVE_RECORD = doc({ id: 'record-00001-r', type: 'record', status: 'active' }, '# Record\n')
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
    expect(board.sessions.latest()!.status).toBe('running')
  })

  /**
   * spec-00001-AC-12.8 — the end of a session is a refresh trigger of its own
   * (issue-00013). A session that wrote nothing leaves no file event for the
   * board to ride on, so without this the board never hears that the slot is
   * free. One signal, not two: the wrap-up rides the same debounce window.
   */
  it('signals the end of a session that changed nothing', async () => {
    const open = await watchingBoard({ 'idea/a.md': ACTIVE_IDEA }, ['-e', ''])
    const { port, board } = open
    const watching = await subscribe(open)

    await fetch(`http://127.0.0.1:${port}/api/sessions`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ sourceId: 'idea-00001-x', targetType: 'prd' }),
    })
    await vi.waitFor(() => expect(board.sessions.latest()!.status).toBe('exited'), SESSION_WAIT)
    await board.sessions.whenFinished()

    await vi.waitFor(() => expect(watching.signals).toBe(1), SIGNAL_WAIT)
    await new Promise((resolve) => setTimeout(resolve, SETTLE))
    expect(watching.signals).toBe(1)
  })

  /**
   * spec-00003-AC-8.3 — a batch of endings is one refresh. The session-end signal
   * goes through the very window a burst of writes goes through (design-00001 §5
   * 刷新合并, the same ~100ms as §6's watcher debounce), so two wrap-ups running
   * back to back in the commit queue are announced once; the panel's per-session
   * notices are the front end's, and are not folded (spec-00003-FR-7).
   */
  it('folds two sessions ending in one batch into a single refresh signal', async () => {
    const agents = scriptedAgents()
    const open = boardOn({ 'spec/b.md': DRAFT_SPEC, 'record/r.md': ACTIVE_RECORD }, undefined, undefined, agents.spawn)
    const { board, docsDir, repoRoot } = open
    await armWatch(board.watcher, docsDir)
    const watching = await subscribe(open)
    const first = (await open.call('POST', '/api/sessions/clarify', { docId: 'spec-00001-b' })).body.id
    const second = (await open.call('POST', '/api/sessions/ask', { docId: 'record-00001-r' })).body.id

    // What the two agents wrote, and its own signal out of the way: what is
    // being counted below is the endings, not the writes.
    appendFileSync(join(docsDir, 'spec/b.md'), '\nasked and answered\n')
    appendFileSync(join(docsDir, 'record/r.md'), '\none more line of evidence\n')
    await vi.waitFor(() => expect(watching.signals).toBeGreaterThanOrEqual(1), SIGNAL_WAIT)
    await new Promise((resolve) => setTimeout(resolve, SETTLE))
    const written = watching.signals

    agents.exit(0)
    agents.exit(1)
    await Promise.all([board.sessions.whenFinished(first), board.sessions.whenFinished(second)])

    await vi.waitFor(() => expect(watching.signals).toBe(written + 1), SIGNAL_WAIT)
    await new Promise((resolve) => setTimeout(resolve, SETTLE))
    expect(watching.signals).toBe(written + 1)
    // And by the time that one signal lands, both wrap-ups are in: the graph the
    // board re-reads is the one after both of them, with nothing left behind.
    expect(git(repoRoot, 'status', '--porcelain', '--', 'docs').trim()).toBe('')
    expect((await open.call('GET', '/api/graph')).body.nodes).toHaveLength(2)
  })

  /**
   * spec-00003-FR-6 on the wire — a session's waiting mark is session state, and
   * the one way session state reaches a board is this signal, after which the
   * board re-reads (spec-00001-FR-42; design-00001 §7's refresh covers the
   * sessions). Nothing else moves here — no file is written and no session ends —
   * so without a signal of its own the mark would sit on the server unseen and
   * the badge could never appear. One signal per flip, on the same window as
   * every other trigger.
   */
  it('signals a session going quiet, and again when it speaks', async () => {
    const agents = scriptedAgents()
    const open = boardOn({ 'spec/b.md': DRAFT_SPEC }, undefined, undefined, agents.spawn, AWAIT_THRESHOLD)
    await armWatch(open.board.watcher, open.docsDir)
    const watching = await subscribe(open)
    await open.call('POST', '/api/sessions/clarify', { docId: 'spec-00001-b' })

    await vi.waitFor(() => expect(watching.signals).toBe(1), SIGNAL_WAIT)
    await new Promise((resolve) => setTimeout(resolve, SETTLE))
    expect(watching.signals).toBe(1)
    const quiet = await open.call('GET', '/api/sessions')
    expect(quiet.body.sessions[0].awaiting).toBe(true)

    agents.say(0, 'here is what I found')

    await vi.waitFor(() => expect(watching.signals).toBe(2), SIGNAL_WAIT)
    const speaking = await open.call('GET', '/api/sessions')
    expect(speaking.body.sessions[0].awaiting).toBe(false)
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
    await vi.waitFor(() => expect(board.sessions.latest()!.status).toBe('exited'), SESSION_WAIT)
    await board.sessions.whenFinished()

    expect(board.sessions.latest()!.outcome).toEqual({
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
    await vi.waitFor(() => expect(board.sessions.latest()!.status).toBe('exited'), SESSION_WAIT)
    await board.sessions.whenFinished()

    const node = (await call('GET', '/api/graph')).body.nodes.find((n: { id: string }) => n.id === 'prd-00001-new')
    expect(node.ok).toBe(false)
    expect(node.problems).toContain('parent does not point at idea-00001-x')
  })

  it('reports a session that produced nothing', async () => {
    const { call, board } = boardOn({ 'idea/a.md': ACTIVE_IDEA })

    await call('POST', '/api/sessions', { sourceId: 'idea-00001-x', targetType: 'prd' })
    await vi.waitFor(() => expect(board.sessions.latest()!.status).toBe('exited'), SESSION_WAIT)
    await board.sessions.whenFinished()

    expect(board.sessions.latest()!.outcome).toEqual({ problems: [], committed: false, error: undefined })
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
    await vi.waitFor(() => expect(board.sessions.latest()!.status).toBe('exited'), SESSION_WAIT)
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
    await vi.waitFor(() => expect(board.sessions.latest()!.status).toBe('exited'), SESSION_WAIT)
    await board.sessions.whenFinished()

    expect(commitCount(repoRoot)).toBe(commits)
    expect(board.sessions.latest()!.outcome!.committed).toBe(false)
  })
})

/**
 * spec-00003-FR-8: every commit the board makes — the four session kinds' wrap-up
 * commits and the write path's own — runs in one serial queue, so sessions ending
 * together neither stage each other's files nor swallow one another
 * (design-00001 §4, §6).
 *
 * The agents here are stand-ins whose exit the test fires: what these tests
 * measure is the order two wrap-ups reach the queue in, and a real process ends
 * when it ends.
 */
describe('several commits at once', () => {
  const DRAFT_SPEC = doc({ id: 'spec-00001-b', type: 'spec', status: 'draft' }, '# Spec\n')
  const ACTIVE_RECORD = doc({ id: 'record-00001-r', type: 'record', status: 'active' }, '# Record\n')

  function scriptedBoard(files: Record<string, string>) {
    const agents = scriptedAgents()
    return { ...boardOn(files, undefined, undefined, agents.spawn), exit: agents.exit }
  }

  /** The files one commit staged, newest commit first at `back = 0`. */
  function commitFiles(repoRoot: string, back: number): string[] {
    return git(repoRoot, 'show', '--name-only', '--pretty=', `HEAD~${back}`).trim().split('\n').filter(Boolean)
  }

  /** What is still uncommitted under docs/ — empty is «nothing was lost». */
  function dirtyDocs(repoRoot: string): string {
    return git(repoRoot, 'status', '--porcelain', '--', 'docs').trim()
  }

  /** Two sessions on two documents, each ready to be ended by the test. */
  async function twoSessions(call: BoardCall): Promise<[string, string]> {
    const first = await call('POST', '/api/sessions/clarify', { docId: 'spec-00001-b' })
    const second = await call('POST', '/api/sessions/ask', { docId: 'record-00001-r' })
    return [first.body.id, second.body.id]
  }

  // spec-00003-AC-8.1
  it('gives two sessions ending one after the other a commit each, staging only its own file', async () => {
    const { call, board, repoRoot, docsDir, exit } = scriptedBoard({
      'spec/b.md': DRAFT_SPEC,
      'record/r.md': ACTIVE_RECORD,
    })
    const commits = commitCount(repoRoot)
    const [first, second] = await twoSessions(call)

    appendFileSync(join(docsDir, 'spec/b.md'), '\nasked and answered\n')
    exit(0)
    await board.sessions.whenFinished(first)
    appendFileSync(join(docsDir, 'record/r.md'), '\none more line of evidence\n')
    exit(1)
    await board.sessions.whenFinished(second)

    expect(commitCount(repoRoot)).toBe(commits + 2)
    // Each names its own kind and its own document (spec-00001-FR-14), and each
    // staged set holds that session's file alone.
    expect(lastCommitMessage(repoRoot)).toBe('wb(ask): record-00001-r')
    expect(commitFiles(repoRoot, 0)).toEqual(['docs/record/r.md'])
    expect(git(repoRoot, 'log', '-2', '--pretty=%s').trim().split('\n').at(-1)).toBe('wb(clarify): spec-00001-b')
    expect(commitFiles(repoRoot, 1)).toEqual(['docs/spec/b.md'])
  })

  // spec-00003-AC-8.2
  it('makes one commit when only one of two sessions changed anything under docs', async () => {
    const { call, board, repoRoot, docsDir, exit } = scriptedBoard({
      'spec/b.md': DRAFT_SPEC,
      'record/r.md': ACTIVE_RECORD,
    })
    const commits = commitCount(repoRoot)
    const [first, second] = await twoSessions(call)

    appendFileSync(join(docsDir, 'spec/b.md'), '\nasked and answered\n')
    // The writer ends first — AC-8.2's own ordering: were the silent session
    // first, its turn would sweep the writer's file (FR-8's attribution
    // boundary, pinned by the AC-8.6 test below).
    exit(0)
    exit(1)
    await Promise.all([board.sessions.whenFinished(first), board.sessions.whenFinished(second)])

    expect(commitCount(repoRoot)).toBe(commits + 1)
    expect(lastCommitMessage(repoRoot)).toBe('wb(clarify): spec-00001-b')
    // The session that wrote nothing wrapped up all the same, with no commit.
    const listed = board.sessions.list()
    expect(listed.find((session) => session.id === second)!.outcome).toEqual({
      docId: 'record-00001-r',
      problems: [],
      committed: false,
      error: undefined,
    })
  })

  // spec-00003-AC-8.6
  it('loses nothing when both sessions wrote before either ended, letting the first sweep the batch', async () => {
    const { call, board, repoRoot, docsDir, exit } = scriptedBoard({
      'spec/b.md': DRAFT_SPEC,
      'record/r.md': ACTIVE_RECORD,
    })
    const commits = commitCount(repoRoot)
    const [first, second] = await twoSessions(call)

    appendFileSync(join(docsDir, 'spec/b.md'), '\nasked and answered\n')
    appendFileSync(join(docsDir, 'record/r.md'), '\none more line of evidence\n')
    exit(0)
    exit(1)
    await Promise.all([board.sessions.whenFinished(first), board.sessions.whenFinished(second)])

    // Content difference carries no attribution (spec-00003-FR-8's boundary):
    // the first to end sweeps the other's write, the second finds no residue
    // and commits nothing — at most two commits, here one, nothing left dirty.
    expect(dirtyDocs(repoRoot)).toBe('')
    expect(commitCount(repoRoot)).toBe(commits + 1)
    expect(lastCommitMessage(repoRoot)).toBe('wb(clarify): spec-00001-b')
    expect(commitFiles(repoRoot, 0).sort()).toEqual(['docs/record/r.md', 'docs/spec/b.md'])
    expect(board.sessions.list().find((session) => session.id === second)!.outcome!.committed).toBe(false)
  })

  // spec-00003-AC-8.4
  it('keeps a user save and a session wrap-up in two commits, neither swallowing the other', async () => {
    const { call, board, repoRoot, docsDir, exit } = scriptedBoard({
      'spec/b.md': DRAFT_SPEC,
      'idea/a.md': ACTIVE_IDEA,
    })
    const commits = commitCount(repoRoot)
    const { body: session } = await call('POST', '/api/sessions/clarify', { docId: 'spec-00001-b' })
    const { body: opened } = await call('GET', '/api/docs/idea-00001-x')

    appendFileSync(join(docsDir, 'spec/b.md'), '\nasked and answered\n')
    // The wrap-up is in flight when the save arrives: the queue orders the two,
    // and the save's write is part of its own turn, so the wrap-up's difference
    // cannot include the edited file either.
    exit(0)
    const saved = await call('PUT', '/api/docs/idea-00001-x', {
      content: `${ACTIVE_IDEA}an edit made by hand\n`,
      baseHash: opened.hash,
    })
    await board.sessions.whenFinished(session.id)

    expect(saved.body.committed).toBe(true)
    expect(commitCount(repoRoot)).toBe(commits + 2)
    expect(commitFiles(repoRoot, 1)).toEqual(['docs/spec/b.md'])
    expect(lastCommitMessage(repoRoot)).toBe('wb(edit): idea-00001-x')
    expect(commitFiles(repoRoot, 0)).toEqual(['docs/idea/a.md'])
    expect(dirtyDocs(repoRoot)).toBe('')
  })

  // spec-00003-AC-8.5
  it('loses nothing when two sessions wrote the same third document, crediting the first to end', async () => {
    const { call, board, repoRoot, docsDir, exit } = scriptedBoard({
      'spec/b.md': DRAFT_SPEC,
      'record/r.md': ACTIVE_RECORD,
      'idea/a.md': ACTIVE_IDEA,
    })
    const commits = commitCount(repoRoot)
    const [first, second] = await twoSessions(call)

    appendFileSync(join(docsDir, 'idea/a.md'), 'a line from the clarify session\n')
    appendFileSync(join(docsDir, 'idea/a.md'), 'a line from the ask session\n')
    exit(0)
    exit(1)
    await Promise.all([board.sessions.whenFinished(first), board.sessions.whenFinished(second)])

    // Both lines are in the repo and nothing is left behind: attribution by end
    // order means the first to end carries the other's line as known noise
    // (decision-00009 §2 第 9 条), never that a change goes missing.
    const committed = git(repoRoot, 'show', 'HEAD:docs/idea/a.md')
    expect(committed).toContain('a line from the clarify session')
    expect(committed).toContain('a line from the ask session')
    expect(dirtyDocs(repoRoot)).toBe('')
    expect(commitCount(repoRoot)).toBeLessThanOrEqual(commits + 2)
    expect(commitFiles(repoRoot, 0)).toContain('docs/idea/a.md')
    expect(lastCommitMessage(repoRoot)).toBe('wb(clarify): spec-00001-b')
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
    await vi.waitFor(() => expect(board.board.sessions.latest()!.status).toBe('exited'), SESSION_WAIT)
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

/**
 * Creating a flow entry document over the wire (spec-00001-FR-53): the prefill
 * writes nothing, and the save is the create branch of the one write path
 * (design-00001 §6, §7).
 */
describe('creating a document', () => {
  const IDEA_TEMPLATE = doc({ id: 'idea-00001-example-slug', type: 'idea', status: 'draft' }, '# Idea: <one line>\n')
  const newIdea = (id: string) => doc({ id, type: 'idea', status: 'draft' }, '# A second idea\n')

  // spec-00001-AC-53.1, first half: the prefill is an allocation, not a write
  it('serves the allocated id prefix and the type template without writing anything', async () => {
    const { call, docsDir } = boardOn({ 'idea/a.md': ACTIVE_IDEA, 'idea/TEMPLATE.md': IDEA_TEMPLATE })

    const { status, body } = await call('GET', '/api/create?type=idea')

    expect(status).toBe(200)
    expect(body).toEqual({ idPrefix: 'idea-00002-', template: IDEA_TEMPLATE })
    expect(existsSync(join(docsDir, 'idea/idea-00002-a-second-idea.md'))).toBe(false)
  })

  // spec-00001-AC-53.2 for the prefill half, and AC-53.6's other reading: a type
  // outside `entry` is refused however the request arrives
  it('answers 422 for a type that is not a flow entry, and for no type at all', async () => {
    const { call } = boardOn({ 'idea/a.md': ACTIVE_IDEA })

    for (const query of ['?type=spec', '?type=', '']) {
      const { status, body } = await call('GET', `/api/create${query}`)
      expect(status).toBe(422)
      expect(body.error).toMatch(/not a flow entry type/)
    }
  })

  // spec-00001-AC-53.1 and rule-00001-AC-26.1: the save is what creates the file
  it('creates the document at the allocated id, as a draft, and commits it', async () => {
    const { call, docsDir, repoRoot } = boardOn({ 'idea/a.md': ACTIVE_IDEA })
    const { body: prefill } = await call('GET', '/api/create?type=idea')
    const id = `${prefill.idPrefix}a-second-idea`

    const { status, body } = await call('POST', '/api/docs', { id, content: newIdea(id) })

    expect(status).toBe(201)
    expect(body.committed).toBe(true)
    expect(readFileSync(join(docsDir, `idea/${id}.md`), 'utf8')).toBe(newIdea(id))
    expect(lastCommitMessage(repoRoot)).toBe(`wb(create): ${id}`)
    // …and the board sees it at once: the create invalidates the parsed tree
    const { body: graph } = await call('GET', '/api/graph')
    expect(graph.nodes.map((node: { id: string }) => node.id)).toEqual(['idea-00001-x', id])
    expect(graph.nodes[1].status).toBe('draft')
  })

  // spec-00001-AC-53.2 with rule-00001-AC-27.1 — refused, and nothing written
  it('answers 422 for a create of a type outside the entry list', async () => {
    const { call, docsDir, repoRoot } = boardOn({ 'idea/a.md': ACTIVE_IDEA })
    const commits = commitCount(repoRoot)
    const content = doc({ id: 'spec-00001-mine', type: 'spec', status: 'draft' }, '# Mine\n')

    const { status, body } = await call('POST', '/api/docs', { id: 'spec-00001-mine', content })

    expect(status).toBe(422)
    expect(body.error).toMatch(/not a flow entry type/)
    expect(existsSync(join(docsDir, 'spec/spec-00001-mine.md'))).toBe(false)
    expect(commitCount(repoRoot)).toBe(commits)
  })

  // spec-00001-AC-53.3
  it('answers 409 for an id that already exists, without overwriting it', async () => {
    const { call, docsDir } = boardOn({ 'idea/a.md': ACTIVE_IDEA })

    const { status, body } = await call('POST', '/api/docs', {
      id: 'idea-00001-x',
      content: newIdea('idea-00001-x'),
    })

    expect(status).toBe(409)
    expect(body.error).toMatch(/already exists/)
    expect(readFileSync(join(docsDir, 'idea/a.md'), 'utf8')).toBe(ACTIVE_IDEA)
  })

  // spec-00001-AC-53.4
  it('answers 422 for a slug with an upper-case letter or a space', async () => {
    const { call } = boardOn({ 'idea/a.md': ACTIVE_IDEA })

    for (const id of ['idea-00002-My Idea', 'idea-00002-MyIdea']) {
      const { status, body } = await call('POST', '/api/docs', { id, content: newIdea(id) })
      expect(status).toBe(422)
      expect(body.error).toMatch(/lower-case hyphenated slug/)
    }
  })

  it('answers 422 for a request that names no id or no content', async () => {
    const { call } = boardOn({ 'idea/a.md': ACTIVE_IDEA })

    expect((await call('POST', '/api/docs', { content: newIdea('idea-00002-x') })).status).toBe(422)
    expect((await call('POST', '/api/docs', { id: 'idea-00002-x' })).status).toBe(422)
  })

  /**
   * spec-00001-AC-53.7 — the board commits `draft` documents by spec, and this
   * repo's pre-commit hook rejects exactly those (decision-00008 §2 第 6 条). The
   * hook is armed for real here: a hand-made commit of the same kind of file is
   * refused, and the board's create goes through all the same.
   */
  it('commits a draft even with a pre-commit hook that rejects drafts', async () => {
    const { call, repoRoot, docsDir } = boardOn({ 'idea/a.md': ACTIVE_IDEA })
    mkdirSync(join(repoRoot, '.githooks'), { recursive: true })
    const hook = join(repoRoot, '.githooks/pre-commit')
    // The repo's own hook, cut down to the check this is about.
    writeFileSync(
      hook,
      [
        '#!/bin/sh',
        'for f in $(git diff --cached --name-only --diff-filter=ACM | grep -E "\\.md$"); do',
        '  if git show ":$f" | grep -Eq "^status:[[:space:]]*draft[[:space:]]*$"; then',
        '    echo "  $f is still draft"; exit 1',
        '  fi',
        'done',
        '',
      ].join('\n'),
    )
    chmodSync(hook, 0o755)
    git(repoRoot, 'config', 'core.hooksPath', '.githooks')

    const id = 'idea-00002-created-under-the-hook'
    const { status, body } = await call('POST', '/api/docs', { id, content: doc({ id, type: 'idea', status: 'draft' }) })

    expect(status).toBe(201)
    expect(body.committed).toBe(true)
    expect(lastCommitMessage(repoRoot)).toBe(`wb(create): ${id}`)
    // …and the hook was live the whole time, which is what makes that mean something.
    writeFileSync(join(docsDir, 'idea/by-hand.md'), doc({ id: 'idea-00003-by-hand', type: 'idea', status: 'draft' }))
    git(repoRoot, 'add', 'docs/idea/by-hand.md')
    expect(() => git(repoRoot, 'commit', '-m', 'by hand')).toThrowError(/is still draft/)
  })
})

/**
 * The session history (spec-00001-FR-54): every session that ends leaves its
 * metadata and its whole transcript under `.whiteboard/sessions/`, which is why
 * the history outlives both the session and the server.
 */
describe('session history', () => {
  const DRAFT_SPEC = doc({ id: 'spec-00001-b', type: 'spec', status: 'draft' }, '# Spec\n')
  const HOLD = ['-e', 'setTimeout(() => {}, 5000)']

  /** Run one audit session to its end, so there is a history to read. */
  async function afterAnAudit(agentArgs = ['-e', "console.log('auditing away')"]) {
    const open = boardOn({ 'spec/b.md': DRAFT_SPEC }, agentArgs)
    await open.call('POST', '/api/sessions/audit', { docId: 'spec-00001-b' })
    await vi.waitFor(() => expect(open.board.sessions.latest()!.status).toBe('exited'), SESSION_WAIT)
    await open.board.sessions.whenFinished()
    return open
  }

  // spec-00001-AC-54.1
  it('lists a session that has ended with its kind, document and exit status', async () => {
    const { call } = await afterAnAudit()

    const { status, body } = await call('GET', '/api/sessions/history')

    expect(status).toBe(200)
    expect(body).toHaveLength(1)
    expect(body[0]).toMatchObject({
      kind: 'audit',
      docId: 'spec-00001-b',
      agent: 'claude',
      status: 'exited',
      exitCode: 0,
    })
    expect(body[0].startedAt <= body[0].endedAt).toBe(true)
  })

  // spec-00001-AC-54.2 — the files are the store, so a restart changes nothing
  it('serves the list and the whole transcript to a board started after a restart', async () => {
    const { repoRoot, docsDir, call } = await afterAnAudit()
    const { body: before } = await call('GET', '/api/sessions/history')

    const restarted = boardOnRepo(repoRoot, docsDir)

    const { body: listed } = await restarted.call('GET', '/api/sessions/history')
    expect(listed.map((entry: { id: string }) => entry.id)).toEqual(before.map((entry: { id: string }) => entry.id))

    const { status, body } = await restarted.call('GET', `/api/sessions/history/${listed[0].id}`)
    expect(status).toBe(200)
    expect(body.meta.kind).toBe('audit')
    expect(body.transcript).toContain('auditing away')
    expect(body.transcript).toContain('session ended with code 0')
  })

  it('answers 404 for a session it has no history of', async () => {
    const { call } = boardOn({})

    for (const id of ['nothing-like-that', '..%2F..%2Fetc%2Fpasswd']) {
      const { status, body } = await call('GET', `/api/sessions/history/${id}`)
      expect(status).toBe(404)
      expect(body.error).toMatch(/no session history/)
    }
  })

  it('lists nothing at all before the first session', async () => {
    const { call } = boardOn({})
    expect((await call('GET', '/api/sessions/history')).body).toEqual([])
  })

  // One unreadable file must not cost the user the rest of the history
  it('leaves a history file it cannot read out of the list', async () => {
    const { call, repoRoot } = await afterAnAudit(['-e', ''])
    writeFileSync(join(repoRoot, '.whiteboard/sessions/half-written.json'), 'not json at all')

    expect((await call('GET', '/api/sessions/history')).body).toHaveLength(1)
  })

  /**
   * spec-00001-AC-54.3 — the history is a record, not a gate: a directory it
   * cannot be written to costs the user that record and nothing else. The commit
   * lands, the board is told, and the failure is a notice on the session.
   */
  it('commits and refreshes all the same when the history cannot be written', async () => {
    const { call, board, repoRoot } = boardOn({ 'spec/b.md': DRAFT_SPEC }, [
      '-e',
      `require('fs').appendFileSync('spec/b.md', '\\nrevised\\n')`,
    ])
    const sessions = join(repoRoot, '.whiteboard/sessions')
    mkdirSync(sessions, { recursive: true })
    chmodSync(sessions, 0o500)

    await call('POST', '/api/sessions/audit', { docId: 'spec-00001-b' })
    await vi.waitFor(() => expect(board.sessions.latest()!.status).toBe('exited'), SESSION_WAIT)
    await board.sessions.whenFinished()

    expect(lastCommitMessage(repoRoot)).toBe('wb(audit): spec-00001-b')
    expect(board.sessions.latest()!.outcome!.committed).toBe(true)
    expect(board.sessions.latest()!.historyError).toBeTruthy()
    expect(board.sessions.attach(board.sessions.latest()!.id, () => {}).buffer).toContain('could not save the session history')
    expect((await call('GET', '/api/sessions/history')).body).toEqual([])
    chmodSync(sessions, 0o700)
  })

  // spec-00001-AC-54.4 — a stopped session is a session that ended, and the
  // metadata says it was stopped (design-00001 §7, spec-00003-FR-4)
  it('lists a stopped session with the exit status it really had', async () => {
    const { call, board } = boardOn({ 'spec/b.md': DRAFT_SPEC }, HOLD)
    const { body: started } = await call('POST', '/api/sessions/audit', { docId: 'spec-00001-b' })

    await call('DELETE', `/api/sessions/${started.id}`)

    const { body } = await call('GET', '/api/sessions/history')
    expect(body).toHaveLength(1)
    expect(body[0]).toMatchObject({ id: board.sessions.latest()!.id, kind: 'audit', status: 'terminated' })
    expect(body[0].exitCode).toBe(board.sessions.latest()!.exitCode)
  })

  it('lists the newest session first', async () => {
    const { call, board } = await afterAnAudit(['-e', ''])
    await call('POST', '/api/sessions/audit', { docId: 'spec-00001-b' })
    await vi.waitFor(() => expect(board.sessions.latest()!.status).toBe('exited'), SESSION_WAIT)
    await board.sessions.whenFinished()

    const { body } = await call('GET', '/api/sessions/history')

    expect(body).toHaveLength(2)
    expect(body[0].startedAt >= body[1].startedAt).toBe(true)
    expect(body[0].id).toBe(board.sessions.latest()!.id)
  })
})

/**
 * Choosing the agent a session runs (spec-00001-FR-55). The flow config has
 * allowed several since the first round; taking the first one was an
 * implementation debt, not a design (decision-00008 §2 第 4 条).
 */
describe('the agent a session runs', () => {
  const DRAFT_SPEC = doc({ id: 'spec-00001-b', type: 'spec', status: 'draft' }, '# Spec\n')
  const ACTIVE_RECORD = doc({ id: 'record-00001-r', type: 'record', status: 'active' }, '# Record\n')

  /** A board on a two-agent config whose pty is a stand-in recording what it spawned. */
  function twoAgentBoard() {
    const { repoRoot, docsDir } = makeRepo({ 'spec/b.md': DRAFT_SPEC, 'record/r.md': ACTIVE_RECORD })
    const config = testConfig()
    config.agents = [
      { name: 'claude', command: 'first-cli', args: [], cwd: 'docs' },
      { name: 'other', command: 'second-cli', args: ['--yolo'], cwd: 'docs' },
    ]
    const spawned: Array<{ command: string; args: string[] }> = []
    const open = boardOnRepo(repoRoot, docsDir, config, (command, args) => {
      spawned.push({ command, args })
      return { onData: () => {}, onExit: () => {}, write: () => {}, resize: () => {}, kill: () => {} }
    })
    return { ...open, spawned }
  }

  // spec-00001-AC-55.1
  it('starts an ask session on the agent the request names', async () => {
    const { call, spawned, board } = twoAgentBoard()

    const { status, body } = await call('POST', '/api/sessions/ask', { docId: 'record-00001-r', agent: 'other' })

    expect(status).toBe(200)
    expect(body.agent).toBe('other')
    expect(spawned).toEqual([{ command: 'second-cli', args: ['--yolo'] }])
    expect(board.sessions.latest()!.agent).toBe('other')
  })

  // spec-00001-AC-55.2 — no name means the first, which is every earlier board
  it('starts an advance on the first configured agent when none is named', async () => {
    const { call, spawned } = twoAgentBoard()

    const { body } = await call('POST', '/api/sessions', { sourceId: 'spec-00001-b', targetType: 'plan' })

    expect(body.agent).toBe('claude')
    expect(spawned).toEqual([{ command: 'first-cli', args: [] }])
  })

  // spec-00001-AC-55.3 — every one of the four kinds refuses a name it has never heard
  it('answers 422 and starts nothing for an agent the config does not declare', async () => {
    const { call, spawned, board } = twoAgentBoard()

    for (const [path, request] of [
      ['/api/sessions', { sourceId: 'spec-00001-b', targetType: 'plan', agent: 'nope' }],
      ['/api/sessions/clarify', { docId: 'spec-00001-b', agent: 'nope' }],
      ['/api/sessions/ask', { docId: 'spec-00001-b', agent: 'nope' }],
      ['/api/sessions/audit', { docId: 'spec-00001-b', agent: 'nope' }],
    ] as const) {
      const { status, body } = await call('POST', path, request)
      expect(status).toBe(422)
      expect(body.error).toMatch(/is not an agent in the flow config/)
    }
    expect(spawned).toEqual([])
    expect(board.sessions.latest()).toBeNull()
  })

  it('answers 422 for an agent that is not a name at all', async () => {
    const { call, spawned } = twoAgentBoard()

    const { status, body } = await call('POST', '/api/sessions/ask', { docId: 'record-00001-r', agent: 3 })

    expect(status).toBe(422)
    expect(body.error).toMatch(/must name one of the agents/)
    expect(spawned).toEqual([])
  })

  // The agent that ran is part of what the history remembers (spec-00001-FR-54)
  it('records which agent ran in the session history', async () => {
    const { call, board } = boardOn({ 'record/r.md': ACTIVE_RECORD })

    await call('POST', '/api/sessions/ask', { docId: 'record-00001-r', agent: 'claude' })
    await vi.waitFor(() => expect(board.sessions.latest()!.status).toBe('exited'), SESSION_WAIT)
    await board.sessions.whenFinished()

    expect((await call('GET', '/api/sessions/history')).body[0].agent).toBe('claude')
  })
})

/**
 * issue-00014 with spec-00001-AC-17.3: the product check is a reading of the
 * disk, not a state the board keeps. A document the user has fixed stops being
 * marked at the very next refresh — no further advance needed.
 */
describe('a product marked anomalous, then fixed on disk', () => {
  const writeProduct = (content: string) =>
    `-e|require('fs').mkdirSync('prd',{recursive:true});require('fs').writeFileSync('prd/new.md',${JSON.stringify(content)})`
  const WITHOUT_PARENT = doc({ id: 'prd-00001-new', type: 'prd', status: 'draft' }, '# New\n')
  const WITH_PARENT = doc({ id: 'prd-00001-new', type: 'prd', status: 'draft', parent: 'idea-00001-x' }, '# New\n')

  /** A signal crosses a watch, a debounce and a socket; a busy suite stretches all three. */
  const REFRESH_WAIT = { timeout: 10_000, interval: 25 }

  /**
   * An advance whose product does not point back at its source: marked, per
   * AC-17.1. The board's watch is armed before the test writes, so the fix that
   * follows is a real refresh — the one FR-42 gives the user for free.
   */
  async function afterAMarkedAdvance() {
    const open = boardOn({ 'idea/a.md': ACTIVE_IDEA }, writeProduct(WITHOUT_PARENT).split('|'))
    await open.call('POST', '/api/sessions', { sourceId: 'idea-00001-x', targetType: 'prd' })
    await vi.waitFor(() => expect(open.board.sessions.latest()!.status).toBe('exited'), SESSION_WAIT)
    await open.board.sessions.whenFinished()
    const node = (await open.call('GET', '/api/graph')).body.nodes.find(
      (found: { id: string }) => found.id === 'prd-00001-new',
    )
    expect(node.ok).toBe(false)
    await armWatch(open.board.watcher, open.docsDir)
    return open
  }

  // spec-00001-AC-17.3
  it('drops the mark on the next refresh once the relation is there', async () => {
    const { call, docsDir } = await afterAMarkedAdvance()

    writeFileSync(join(docsDir, 'prd/new.md'), WITH_PARENT)

    const graph = await vi.waitFor(async () => {
      const { body } = await call('GET', '/api/graph')
      const found = body.nodes.find((node: { id: string }) => node.id === 'prd-00001-new')
      expect(found.ok).toBe(true)
      expect(found.problems).toEqual([])
      return body
    }, REFRESH_WAIT)

    expect(graph.issues).toEqual([])
    // …and the edge the relation declares is on the graph, as AC-17.2 has it
    expect(graph.edges).toContainEqual({
      from: 'prd-00001-new',
      to: 'idea-00001-x',
      relation: 'parent',
      ok: true,
      declaredTargets: ['idea-00001-x'],
    })
  })

  // The mark is still a mark while the document is still wrong (AC-17.1 on a
  // second reading, which is the one the defect broke).
  it('keeps marking it on every refresh while the relation is still missing', async () => {
    const { call } = await afterAMarkedAdvance()

    const { body } = await call('GET', '/api/graph')

    const node = body.nodes.find((found: { id: string }) => found.id === 'prd-00001-new')
    expect(node.ok).toBe(false)
    expect(node.problems).toContain('parent does not point at idea-00001-x')
  })

  it('marks nothing once the product itself is gone from disk', async () => {
    const { call, docsDir } = await afterAMarkedAdvance()

    rmSync(join(docsDir, 'prd/new.md'))

    const graph = await vi.waitFor(async () => {
      const { body } = await call('GET', '/api/graph')
      expect(body.nodes.map((node: { id: string }) => node.id)).toEqual(['idea-00001-x'])
      return body
    }, REFRESH_WAIT)

    expect(graph.issues).toEqual([])
  })
})

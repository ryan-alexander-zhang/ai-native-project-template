import type { Server } from 'node:http'
import { appendFileSync, chmodSync, existsSync, mkdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { taskInstruction } from '../src/advance.ts'
import type { SubmitResult } from '../src/annotations.ts'
import type { AskThread } from '../src/askStore.ts'
import type { AgentConfig } from '../src/config.ts'
import type { SpawnHeadless } from '../src/headless.ts'
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
  // The seam an ask call runs on (design-00001 §10.1); given only by the tests
  // that script one, so the rest run the real child process.
  spawnHeadless?: SpawnHeadless,
) {
  const board = new Board({ repoRoot, docsDir, config, spawn, spawnHeadless, awaitThresholdMs })
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
    // Once, like a process: whichever ends it — the script or a signal — the
    // second attempt is nothing, so no wrap-up can be run twice.
    let gone = false
    const end = (exitCode: number) => {
      if (gone) return
      gone = true
      for (const listener of listeners) listener({ exitCode })
    }
    exits.push(end)
    says.push((data) => {
      for (const reader of readers) reader(data)
    })
    return {
      onData: (listener) => void readers.push(listener),
      onExit: (listener) => void listeners.push(listener),
      write: () => {},
      resize: () => {},
      // A stand-in that hears the polite signal: what a stop and a shutdown wait
      // on is this exit (spec-00003-FR-9, issue-00012).
      kill: () => end(0),
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
    expect(body).toMatchObject({ committed: true, status: 'active' })
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
      expect(body).toMatchObject({ committed: true, status: 'resolved' })
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

  // spec-00003-AC-3.8 — a declared cap of one refuses the second start like any cap
  it('refuses a second session outright when the cap is one', async () => {
    const { call } = cappedBoard({ 'idea/a.md': ACTIVE_IDEA, 'prd/p.md': RELATED_PRD }, 1)
    await call('POST', '/api/sessions', { sourceId: 'idea-00001-x', targetType: 'prd' })

    const { status, body } = await call('POST', '/api/sessions', { sourceId: 'prd-00001-p', targetType: 'spec' })

    expect(status).toBe(409)
    expect(body.reason).toBe('cap-reached')
    expect((await call('GET', '/api/sessions')).body.sessions).toHaveLength(1)
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
    await call('POST', '/api/sessions', { sourceId: 'idea-00001-x', targetType: 'prd' })

    const { status, body } = await call('POST', '/api/sessions', { sourceId: 'prd-00001-p', targetType: 'spec' })

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
    await call('POST', '/api/sessions', { sourceId: 'idea-00001-x', targetType: 'prd' })

    const { status, body } = await call('POST', '/api/sessions', { sourceId: 'prd-00001-p', targetType: 'spec' })

    expect(status).toBe(409)
    expect(body.reason).toBe('cap-reached')
    expect(body.error).toMatch(/max_sessions/)
    expect((await call('GET', '/api/sessions')).body.sessions).toHaveLength(1)
  })

  // spec-00003-AC-3.3 — a slot freed by an ending session is a slot to start in
  it('starts a session at the cap once one of the running ones has ended', async () => {
    const { call, board } = cappedBoard({ 'idea/a.md': ACTIVE_IDEA, 'prd/p.md': RELATED_PRD }, 1, ['-e', ''])
    await call('POST', '/api/sessions', { sourceId: 'idea-00001-x', targetType: 'prd' })
    await vi.waitFor(() => expect(board.sessions.latest()!.status).toBe('exited'), SESSION_WAIT)
    await board.sessions.whenFinished()

    expect((await call('POST', '/api/sessions', { sourceId: 'prd-00001-p', targetType: 'spec' })).status).toBe(200)
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
    await call('POST', '/api/sessions', { sourceId: 'prd-00001-p', targetType: 'spec' })

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
    await call('POST', '/api/sessions', { sourceId: 'idea-00001-x', targetType: 'prd' })
    await call('POST', '/api/sessions', { sourceId: 'prd-00001-p', targetType: 'spec' })

    await vi.waitFor(async () => {
      const { body } = await call('GET', '/api/sessions')
      expect(body.sessions.map((session: SessionListing) => session.awaiting)).toEqual([true, true])
    }, SESSION_WAIT)
  })
})

/**
 * The clarify session over the wire (spec-00001-FR-9): the same channel and the
 * same concurrency rules as an advance, with its own ruling. Nothing here writes
 * a document — the session's agent does, and the board commits what it left.
 */
describe('clarify sessions', () => {
  const DRAFT_SPEC = doc({ id: 'spec-00001-b', type: 'spec', status: 'draft' }, '# Spec\n')
  const DRAFT_DESIGN = doc({ id: 'design-00001-d', type: 'design', status: 'draft' }, '# Design\n')
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

  // spec-00001-AC-19.2
  it('answers 409 and starts nothing when the target document was deleted', async () => {
    const { call, board, docsDir } = boardOn({ 'spec/b.md': DRAFT_SPEC })
    rmSync(join(docsDir, 'spec/b.md'))

    const { status, body } = await call('POST', '/api/sessions/clarify', { docId: 'spec-00001-b' })

    expect(status).toBe(409)
    expect(body.error).toMatch(/refresh the board/)
    // The third reason a start is refused (design-00001 §7), told apart from the
    // two concurrency ones.
    expect(body.reason).toBe('doc-missing')
    expect(board.sessions.latest()).toBeNull()
  })

  /**
   * spec-00003-AC-2.1 as the twenty-first round amends its example: an advance
   * running, a clarify refused on the same document. The Given was an ask
   * before — an ask takes no document any more, and that direction is now
   * asserted the other way round by spec-00005-AC-6.2.
   */
  it('answers 409 for a clarify on the document an advance session is running on', async () => {
    const { call, board } = boardOn({ 'spec/b.md': DRAFT_SPEC }, HOLD)
    await call('POST', '/api/sessions', { sourceId: 'spec-00001-b', targetType: 'plan' })

    const { status, body } = await call('POST', '/api/sessions/clarify', { docId: 'spec-00001-b' })

    expect(status).toBe(409)
    expect(body.reason).toBe('doc-busy')
    expect(board.sessions.latest()).toMatchObject({ kind: 'advance', sourceId: 'spec-00001-b', status: 'running' })
  })

  /**
   * spec-00003-AC-1.1 as amended: a clarify running on one document, an audit
   * started on another. The example was an ask, whose form has no terminal at
   * all now and moved to spec-00005.
   */
  it('starts an audit on another document while a clarify session runs', async () => {
    const { call, board } = boardOn({ 'spec/b.md': DRAFT_SPEC, 'design/d.md': DRAFT_DESIGN }, HOLD)
    await call('POST', '/api/sessions/clarify', { docId: 'spec-00001-b' })

    const { status, body } = await call('POST', '/api/sessions/audit', { docId: 'design-00001-d' })

    expect(status).toBe(200)
    expect(body.status).toBe('running')
    expect(board.sessions.list().map((session) => [session.kind, session.status])).toEqual([
      ['clarify', 'running'],
      ['audit', 'running'],
    ])
  })

  it('answers 422 when the request names no document', async () => {
    const { call } = boardOn({ 'spec/b.md': DRAFT_SPEC })
    expect((await call('POST', '/api/sessions/clarify', {})).status).toBe(422)
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

  // spec-00003-AC-2.1 the other way round: the audit excludes the rest on its
  // document — the terminal kinds, that is; an ask is no longer among them
  // (spec-00005-FR-6)
  it('answers 409 for a clarify and an advance while an audit session is running', async () => {
    const { call, board } = boardOn({ 'spec/b.md': DRAFT_SPEC }, HOLD)
    await call('POST', '/api/sessions/audit', { docId: 'spec-00001-b' })

    for (const [path, request] of [
      ['/api/sessions/clarify', { docId: 'spec-00001-b' }],
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
    const session = board.sessions.start({ kind: 'audit', sourceId: 'idea-00001-x', instruction: 'audit this' })
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
    await vi.waitFor(() => expect(typed).toEqual(['audit this', '{"cols":9,"rows":9}']))
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
    expect(typed).toEqual(['audit this'])
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
  const DRAFT_DESIGN = doc({ id: 'design-00001-d', type: 'design', status: 'draft' }, '# Design\n')
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
    const open = boardOn({ 'spec/b.md': DRAFT_SPEC, 'design/d.md': DRAFT_DESIGN }, undefined, undefined, agents.spawn)
    const { board, docsDir, repoRoot } = open
    await armWatch(board.watcher, docsDir)
    const watching = await subscribe(open)
    const first = (await open.call('POST', '/api/sessions/clarify', { docId: 'spec-00001-b' })).body.id
    const second = (await open.call('POST', '/api/sessions/audit', { docId: 'design-00001-d' })).body.id

    // What the two agents wrote, and its own signal out of the way: what is
    // being counted below is the endings, not the writes.
    appendFileSync(join(docsDir, 'spec/b.md'), '\nasked and answered\n')
    appendFileSync(join(docsDir, 'design/d.md'), '\none more line of evidence\n')
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
  const DRAFT_DESIGN = doc({ id: 'design-00001-d', type: 'design', status: 'draft' }, '# Design\n')

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
    const second = await call('POST', '/api/sessions/audit', { docId: 'design-00001-d' })
    return [first.body.id, second.body.id]
  }

  // spec-00003-AC-8.1
  it('gives two sessions ending one after the other a commit each, staging only its own file', async () => {
    const { call, board, repoRoot, docsDir, exit } = scriptedBoard({
      'spec/b.md': DRAFT_SPEC,
      'design/d.md': DRAFT_DESIGN,
    })
    const commits = commitCount(repoRoot)
    const [first, second] = await twoSessions(call)

    appendFileSync(join(docsDir, 'spec/b.md'), '\nasked and answered\n')
    exit(0)
    await board.sessions.whenFinished(first)
    appendFileSync(join(docsDir, 'design/d.md'), '\none more line of evidence\n')
    exit(1)
    await board.sessions.whenFinished(second)

    expect(commitCount(repoRoot)).toBe(commits + 2)
    // Each names its own kind and its own document (spec-00001-FR-14), and each
    // staged set holds that session's file alone.
    expect(lastCommitMessage(repoRoot)).toBe('wb(audit): design-00001-d')
    expect(commitFiles(repoRoot, 0)).toEqual(['docs/design/d.md'])
    expect(git(repoRoot, 'log', '-2', '--pretty=%s').trim().split('\n').at(-1)).toBe('wb(clarify): spec-00001-b')
    expect(commitFiles(repoRoot, 1)).toEqual(['docs/spec/b.md'])
  })

  // spec-00003-AC-8.2
  it('makes one commit when only one of two sessions changed anything under docs', async () => {
    const { call, board, repoRoot, docsDir, exit } = scriptedBoard({
      'spec/b.md': DRAFT_SPEC,
      'design/d.md': DRAFT_DESIGN,
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
      docId: 'design-00001-d',
      problems: [],
      committed: false,
      error: undefined,
    })
  })

  // spec-00003-AC-5.2 — no terminal was ever attached: the wrap-up is unchanged
  it('wraps up a session nobody is watching, commit and history included', async () => {
    const { call, board, repoRoot, docsDir, exit } = scriptedBoard({ 'spec/b.md': DRAFT_SPEC })
    const commits = commitCount(repoRoot)
    const { body: session } = await call('POST', '/api/sessions/clarify', { docId: 'spec-00001-b' })

    appendFileSync(join(docsDir, 'spec/b.md'), '\nasked and answered\n')
    exit(0)
    await board.sessions.whenFinished(session.id)

    expect(commitCount(repoRoot)).toBe(commits + 1)
    expect(lastCommitMessage(repoRoot)).toBe('wb(clarify): spec-00001-b')
    const history = (await call('GET', '/api/sessions/history')).body as Array<{ id: string }>
    expect(history.map((entry) => entry.id)).toContain(session.id)
  })

  // spec-00003-AC-8.6
  it('loses nothing when both sessions wrote before either ended, letting the first sweep the batch', async () => {
    const { call, board, repoRoot, docsDir, exit } = scriptedBoard({
      'spec/b.md': DRAFT_SPEC,
      'design/d.md': DRAFT_DESIGN,
    })
    const commits = commitCount(repoRoot)
    const [first, second] = await twoSessions(call)

    appendFileSync(join(docsDir, 'spec/b.md'), '\nasked and answered\n')
    appendFileSync(join(docsDir, 'design/d.md'), '\none more line of evidence\n')
    exit(0)
    exit(1)
    await Promise.all([board.sessions.whenFinished(first), board.sessions.whenFinished(second)])

    // Content difference carries no attribution (spec-00003-FR-8's boundary):
    // the first to end sweeps the other's write, the second finds no residue
    // and commits nothing — at most two commits, here one, nothing left dirty.
    expect(dirtyDocs(repoRoot)).toBe('')
    expect(commitCount(repoRoot)).toBe(commits + 1)
    expect(lastCommitMessage(repoRoot)).toBe('wb(clarify): spec-00001-b')
    expect(commitFiles(repoRoot, 0).sort()).toEqual(['docs/design/d.md', 'docs/spec/b.md'])
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
      'design/d.md': DRAFT_DESIGN,
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
 * spec-00003-FR-9's shutdown half: a normal shutdown is a terminate on every
 * running session — process ended, history written, commit made through the one
 * serial queue — and nothing of the registry outlives the process, so the next
 * boot's panel starts empty (design-00001 §5 关停收尾).
 */
describe('shutting the board down', () => {
  const DRAFT_SPEC = doc({ id: 'spec-00001-b', type: 'spec', status: 'draft' }, '# Spec\n')
  const DRAFT_DESIGN = doc({ id: 'design-00001-d', type: 'design', status: 'draft' }, '# Design\n')

  /** A board whose two sessions are running and have each written something. */
  async function twoSessionsThatWrote() {
    const agents = scriptedAgents()
    const open = boardOn({ 'spec/b.md': DRAFT_SPEC, 'design/d.md': DRAFT_DESIGN }, undefined, undefined, agents.spawn)
    const first = (await open.call('POST', '/api/sessions/clarify', { docId: 'spec-00001-b' })).body.id
    const second = (await open.call('POST', '/api/sessions/audit', { docId: 'design-00001-d' })).body.id
    appendFileSync(join(open.docsDir, 'spec/b.md'), '\nasked and answered\n')
    appendFileSync(join(open.docsDir, 'design/d.md'), '\none more line of evidence\n')
    return { ...open, sessions: [first, second] as [string, string] }
  }

  // spec-00003-AC-9.3
  it('wraps up every running session, and the next boot lists none of them', async () => {
    const open = await twoSessionsThatWrote()
    const commits = commitCount(open.repoRoot)

    await open.board.shutdown()

    // Each ended the way a stop ends one, and each wrap-up is in: nothing under
    // docs/ is left dirty and both writes are in the tree. Which of the two
    // commits carries which line is FR-8's end-order boundary (AC-8.6), so what
    // is pinned here is only that nothing was lost and nothing piled up.
    expect(open.board.sessions.list().map((session) => session.status)).toEqual(['terminated', 'terminated'])
    expect(git(open.repoRoot, 'status', '--porcelain', '--', 'docs').trim()).toBe('')
    expect(commitCount(open.repoRoot)).toBeGreaterThan(commits)
    expect(commitCount(open.repoRoot)).toBeLessThanOrEqual(commits + 2)
    expect(git(open.repoRoot, 'show', 'HEAD:docs/spec/b.md')).toContain('asked and answered')
    expect(git(open.repoRoot, 'show', 'HEAD:docs/design/d.md')).toContain('one more line of evidence')

    // A restart is a restart: the registry was memory, so the panel starts empty
    // — and both transcripts are where they are kept (spec-00001-FR-54).
    const rebooted = boardOnRepo(open.repoRoot, open.docsDir)
    expect((await rebooted.call('GET', '/api/sessions')).body.sessions).toEqual([])
    const history = (await rebooted.call('GET', '/api/sessions/history')).body
    expect(history.map((entry: { id: string }) => entry.id).sort()).toEqual([...open.sessions].sort())
    expect(history.every((entry: { status: string }) => entry.status === 'terminated')).toBe(true)
    expect((await rebooted.call('GET', `/api/sessions/history/${open.sessions[0]}`)).body.transcript).toContain(
      'session ended with code',
    )
  })

  // spec-00003-AC-9.3 — one shutdown however many signals ask for it: a second
  // one must not put a second commit on wrap-ups that are already done.
  it('does nothing on a second shutdown', async () => {
    const open = await twoSessionsThatWrote()
    await open.board.shutdown()
    const commits = commitCount(open.repoRoot)
    const settled = open.board.sessions.list()

    await open.board.shutdown()

    expect(commitCount(open.repoRoot)).toBe(commits)
    expect(open.board.sessions.list()).toEqual(settled)
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
  const DRAFT_DESIGN = doc({ id: 'design-00001-d', type: 'design', status: 'draft' }, '# Design\n')

  /** A board on a two-agent config whose pty is a stand-in recording what it spawned. */
  function twoAgentBoard() {
    const { repoRoot, docsDir } = makeRepo({ 'spec/b.md': DRAFT_SPEC, 'design/d.md': DRAFT_DESIGN })
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

  // spec-00001-AC-55.1 — the example was an ask, whose form starts no terminal
  // any more; the ask half of agent choice is spec-00005-AC-2.3's
  it('starts an audit session on the agent the request names', async () => {
    const { call, spawned, board } = twoAgentBoard()

    const { status, body } = await call('POST', '/api/sessions/audit', { docId: 'design-00001-d', agent: 'other' })

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
      ['/api/sessions/ask', { docId: 'spec-00001-b', question: 'why this way?', agent: 'nope' }],
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

    const { status, body } = await call('POST', '/api/sessions/audit', { docId: 'design-00001-d', agent: 3 })

    expect(status).toBe(422)
    expect(body.error).toMatch(/must name one of the agents/)
    expect(spawned).toEqual([])
  })

  // The agent that ran is part of what the history remembers (spec-00001-FR-54)
  it('records which agent ran in the session history', async () => {
    const { call, board } = boardOn({ 'design/d.md': DRAFT_DESIGN })

    await call('POST', '/api/sessions/audit', { docId: 'design-00001-d', agent: 'claude' })
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

/**
 * The registry's second form over the wire (spec-00005): a question, a captured
 * headless call, no terminal anywhere. What is scripted here is the call itself
 * — what it was spawned with, what it printed and how it ended — because that is
 * what every one of these rulings is about (design-00001 §10.1).
 */
describe('ask threads', () => {
  const DRAFT_SPEC = doc({ id: 'spec-00001-b', type: 'spec', status: 'draft', parent: 'prd-00001-p' }, '# Spec\n')
  const RELATED_PRD = doc({ id: 'prd-00001-p', type: 'prd', status: 'active' }, '# Prd\n')
  const ACTIVE_RECORD = doc({ id: 'record-00001-r', type: 'record', status: 'active' }, '# Record\n')
  const BROKEN = doc({ id: 'nope', type: 'spec', status: 'draft' }, '# Broken\n')
  const TREE = { 'spec/b.md': DRAFT_SPEC, 'prd/p.md': RELATED_PRD }

  /** What a `claude-json` call prints: the answer, and the id its follow-up resumes. */
  const ANSWER = (text: string, resumeId = 'cli-1') => JSON.stringify({ result: text, session_id: resumeId })

  /**
   * Stand-in headless calls (design-00001 §10.1): what each was spawned with is
   * recorded, and what it prints and how it ends is the test's to say — an ask
   * ruling is about the argv and the exit, never about a real CLI.
   */
  function scriptedCalls() {
    const spawned: Array<{ command: string; args: string[]; cwd: string }> = []
    const ends: Array<(exitCode: number, stdout?: string, stderr?: string) => void> = []
    const spawnHeadless: SpawnHeadless = (command, args, cwd) => {
      spawned.push({ command, args, cwd })
      const outs: Array<(chunk: string) => void> = []
      const errs: Array<(chunk: string) => void> = []
      const exits: Array<(event: { exitCode: number }) => void> = []
      // Once, like a process: whichever ends it — the script or a signal — the
      // second attempt is nothing, so no wrap-up runs twice.
      let gone = false
      const end = (exitCode: number, stdout = '', stderr = '') => {
        if (gone) return
        gone = true
        for (const listener of outs) listener(stdout)
        for (const listener of errs) listener(stderr)
        for (const listener of exits) listener({ exitCode })
      }
      ends.push(end)
      return {
        onStdout: (listener) => void outs.push(listener),
        onStderr: (listener) => void errs.push(listener),
        onExit: (listener) => void exits.push(listener),
        kill: () => end(143),
      }
    }
    return {
      spawnHeadless,
      spawned,
      /** The payload argument of the nth call: the whole of what the CLI was asked. */
      payload: (index: number) => spawned[index]!.args.at(-1)!,
      /** End the nth call: what it printed and how it exited, in that order. */
      end: (index: number, printed: { stdout?: string; stderr?: string; exitCode?: number } = {}) =>
        ends[index]!(printed.exitCode ?? 0, printed.stdout ?? '', printed.stderr ?? ''),
    }
  }

  /** A pty stand-in that records the terminal sessions started, and outlives any test. */
  function recordingPty() {
    const started: string[] = []
    const spawn = (command: string) => {
      started.push(command)
      return { onData: () => {}, onExit: () => {}, write: () => {}, resize: () => {}, kill: () => {} }
    }
    return { spawn, started }
  }

  /** A board whose ask calls are scripted and whose terminal sessions are recorded. */
  function askBoard(
    files: Record<string, string> = TREE,
    options: {
      agents?: AgentConfig[]
      maxSessions?: number
      awaitThresholdMs?: number
      /** An existing repo instead of a fresh one: a second board on it is a restart. */
      on?: { repoRoot: string; docsDir: string }
    } = {},
  ) {
    const { repoRoot, docsDir } = options.on ?? makeRepo(files)
    const config = testConfig()
    if (options.agents) config.agents = options.agents
    if (options.maxSessions) config.maxSessions = options.maxSessions
    const calls = scriptedCalls()
    const pty = recordingPty()
    const open = boardOnRepo(repoRoot, docsDir, config, pty.spawn, options.awaitThresholdMs, calls.spawnHeadless)
    return { ...open, ...calls, terminals: pty.started }
  }

  /** The ask list of a document, as the front end reads it (design-00001 §7). */
  const threadsOf = async (call: BoardCall, docId: string): Promise<AskThread[]> =>
    (await call('GET', `/api/asks/${docId}`)).body.threads

  /** Submit a question and wait out the call the test then ends itself. */
  const ask = (call: BoardCall, body: Record<string, unknown>) => call('POST', '/api/sessions/ask', body)

  // spec-00005-AC-1.1 — the whole of a first call's payload, and no terminal
  // spec-00001-AC-47.1 — the handoff guard: an ask starts no terminal-form session
  it('starts a headless first call carrying the paths, the read-only nature and the question', async () => {
    const { call, payload, terminals } = askBoard()

    const { status, body } = await ask(call, { docId: 'spec-00001-b', question: 'why two gates?' })

    expect(status).toBe(200)
    expect(body.threadId).toBe('t-1')
    expect(payload(0)).toContain('spec/b.md')
    expect(payload(0)).toContain('prd/p.md')
    expect(payload(0)).toContain('Modify no file')
    expect(payload(0).endsWith('why two gates?')).toBe(true)
    expect(terminals).toEqual([])
  })

  // spec-00005-AC-1.3 — an ask is bound by neither type nor status
  it('starts a call on an active record like any other document', async () => {
    const { call, board, spawned } = askBoard({ 'record/r.md': ACTIVE_RECORD })

    const { status, body } = await ask(call, { docId: 'record-00001-r', question: 'what does this verify?' })

    expect(status).toBe(200)
    expect(board.sessions.list()[0]).toMatchObject({ id: body.sessionId, kind: 'ask', status: 'running' })
    expect(spawned).toHaveLength(1)
  })

  // spec-00005-AC-2.1 — the follow-up resumes that thread's own conversation
  it('resumes the thread on a follow-up, carrying the question alone', async () => {
    const { call, board, payload, spawned, end } = askBoard()
    const first = await ask(call, { docId: 'spec-00001-b', question: 'why two gates?' })
    end(0, { stdout: ANSWER('because they are cheap to check') })
    await board.sessions.whenFinished(first.body.sessionId)

    await ask(call, { docId: 'spec-00001-b', question: 'and the third?', threadId: 't-1' })

    expect(spawned[1]!.args).toContain('cli-1')
    expect(payload(1)).toBe('and the third?')
    const [thread] = await threadsOf(call, 'spec-00001-b')
    expect(thread!.resumeId).toBe('cli-1')
    expect(thread!.agent).toBe('claude')
    expect(thread!.exchanges).toHaveLength(2)
    expect(thread!.exchanges[0]).toMatchObject({ answer: 'because they are cheap to check', outcome: 'answered' })
    // Both times are recorded and in order; a scripted call can answer inside
    // the same millisecond it was asked, so they may be equal.
    expect(thread!.exchanges[0]!.askedAt).toMatch(/^\d{4}-\d{2}-\d{2}T.+Z$/)
    expect(thread!.exchanges[0]!.answeredAt! >= thread!.exchanges[0]!.askedAt).toBe(true)
    expect(thread!.exchanges[1]).toMatchObject({ question: 'and the third?', outcome: 'running' })
  })

  // spec-00005-AC-2.2 — a new question is a thread of its own, and no resume
  it('opens a second thread for a new question, whose first call resumes nothing', async () => {
    const { call, board, spawned, end } = askBoard()
    const first = await ask(call, { docId: 'spec-00001-b', question: 'why two gates?' })
    end(0, { stdout: ANSWER('because they are cheap') })
    await board.sessions.whenFinished(first.body.sessionId)

    const { body } = await ask(call, { docId: 'spec-00001-b', question: 'a different matter entirely' })

    expect(body.threadId).toBe('t-2')
    expect(spawned[1]!.args).not.toContain('cli-1')
    expect((await threadsOf(call, 'spec-00001-b')).map((thread) => thread.id)).toEqual(['t-1', 't-2'])
  })

  /**
   * spec-00005-AC-2.3 — the choice is narrowed to the agents that declare a
   * headless form, a new thread may take any of them, and a follow-up takes the
   * one its thread was opened with: a resume id belongs to that CLI alone.
   */
  it('offers only the agents that declare a headless form, and keeps a thread on its own', async () => {
    const headless = testConfig().agents[0]!.headless
    const { call, board, spawned, end } = askBoard(TREE, {
      agents: [
        { name: 'plain', command: 'node', args: [], cwd: 'docs' },
        { name: 'first-asker', command: 'node', args: [], cwd: 'docs', headless },
        { name: 'second-asker', command: 'node', args: [], cwd: 'docs', headless },
      ],
    })

    const refused = await ask(call, { docId: 'spec-00001-b', question: 'why?', agent: 'plain' })
    // No name means the first agent that declares a form, not the first declared.
    const defaulted = await ask(call, { docId: 'spec-00001-b', question: 'why two gates?' })
    const chosen = await ask(call, { docId: 'prd-00001-p', question: 'why now?', agent: 'second-asker' })
    end(1, { stdout: ANSWER('because it is cheap') })
    await board.sessions.whenFinished(chosen.body.sessionId)
    // The follow-up names the other agent all the same: the resume id is the one
    // that answered's, and no other CLI could take it.
    await ask(call, { docId: 'prd-00001-p', question: 'go on', threadId: 't-1', agent: 'first-asker' })

    expect(refused.status).toBe(422)
    expect(refused.body.error).toMatch(/declares no headless form/)
    expect(spawned).toHaveLength(3)
    expect(board.sessions.list().map((session) => session.agent)).toEqual([
      'first-asker',
      'second-asker',
      'second-asker',
    ])
    expect(defaulted.body.threadId).toBe('t-1')
  })

  // spec-00005-AC-4.1 — a call over a draft leaves its status alone and commits nothing
  it('leaves the document and the repository untouched when a call finishes', async () => {
    const { call, board, repoRoot, docsDir, end } = askBoard()
    const commits = commitCount(repoRoot)
    const { body } = await ask(call, { docId: 'spec-00001-b', question: 'why two gates?' })

    end(0, { stdout: ANSWER('because they are cheap') })
    await board.sessions.whenFinished(body.sessionId)

    expect(readFileSync(join(docsDir, 'spec/b.md'), 'utf8')).toBe(DRAFT_SPEC)
    expect((await call('GET', '/api/graph')).body.nodes.find((n: { id: string }) => n.id === 'spec-00001-b').status).toBe('draft')
    expect(commitCount(repoRoot)).toBe(commits)
  })

  // spec-00005-AC-4.2 — the read-only flag is in the command line that ran, not
  // left to the agent's own restraint
  it('runs the declared read-only flags on the actual command line', async () => {
    const { call, spawned } = askBoard(TREE, {
      agents: [
        {
          name: 'claude',
          command: 'claude',
          args: ['--interactive-only'],
          cwd: 'docs',
          headless: {
            first: ['-p', '--permission-mode', 'plan', '{question}'],
            resume: ['-p', '--permission-mode', 'plan', '--resume', '{session}', '{question}'],
            capture: 'claude-json',
          },
        },
      ],
    })

    await ask(call, { docId: 'spec-00001-b', question: 'why two gates?' })

    expect(spawned[0]!.command).toBe('claude')
    expect(spawned[0]!.args.slice(0, 3)).toEqual(['-p', '--permission-mode', 'plan'])
    // The entry's own args are the interactive form's, and stay out of it.
    expect(spawned[0]!.args).not.toContain('--interactive-only')
  })

  /**
   * spec-00005-AC-4.3 — an ask ending first takes nothing of what another
   * session wrote: it makes no commit at all, so the residue is still there for
   * the advance to carry off on its own terms (spec-00003-FR-8).
   */
  it('commits nothing when it ends before an advance that has written under docs', async () => {
    const { call, board, repoRoot, docsDir, end } = askBoard()
    const commits = commitCount(repoRoot)
    await call('POST', '/api/sessions', { sourceId: 'prd-00001-p', targetType: 'spec' })
    appendFileSync(join(docsDir, 'prd/p.md'), '\nwritten by the advance\n')
    const { body } = await ask(call, { docId: 'spec-00001-b', question: 'why two gates?' })

    end(0, { stdout: ANSWER('because') })
    await board.sessions.whenFinished(body.sessionId)

    expect(commitCount(repoRoot)).toBe(commits)
    expect(git(repoRoot, 'status', '--porcelain', '--', 'docs')).toContain('prd/p.md')
  })

  // spec-00005-AC-5.1 — the list outlives the process, and so does the resume id
  it('serves the questions and answers of an earlier run, and resumes from them', async () => {
    const before = askBoard()
    const opened = await ask(before.call, { docId: 'spec-00001-b', question: 'why two gates?' })
    before.end(0, { stdout: ANSWER('because they are cheap') })
    await before.board.sessions.whenFinished(opened.body.sessionId)

    // The same repository, a second board: everything the registry held is gone,
    // and the list is what is left (design-00001 §5, spec-00005-FR-5).
    const after = askBoard(TREE, { on: { repoRoot: before.repoRoot, docsDir: before.docsDir } })
    const threads = await threadsOf(after.call, 'spec-00001-b')
    await ask(after.call, { docId: 'spec-00001-b', question: 'and the third?', threadId: 't-1' })

    expect(after.board.sessions.list()).toHaveLength(1)
    expect(threads[0]!.exchanges[0]).toMatchObject({ question: 'why two gates?', answer: 'because they are cheap' })
    expect(after.spawned[0]!.args).toContain('cli-1')
    expect(after.payload(0)).toBe('and the third?')
  })

  /**
   * spec-00005-AC-5.2 — the ask list is board state: nothing of it is tracked by
   * git, and no question or answer reaches a document.
   */
  it('keeps the list out of git and out of the docs tree', async () => {
    const { call, board, repoRoot, docsDir, end } = askBoard()
    const { body } = await ask(call, { docId: 'spec-00001-b', question: 'why two gates?' })
    end(0, { stdout: ANSWER('because they are cheap') })
    await board.sessions.whenFinished(body.sessionId)

    expect(existsSync(join(repoRoot, '.whiteboard/asks/spec-00001-b.json'))).toBe(true)
    expect(git(repoRoot, 'ls-files')).not.toContain('.whiteboard')
    expect(readFileSync(join(docsDir, 'spec/b.md'), 'utf8')).not.toContain('why two gates?')
  })

  // spec-00005-AC-5.3 — nothing on disk may say «in progress» when nothing is
  it('writes off a call the last process was killed with, at the next boot', async () => {
    const { call, repoRoot, docsDir } = askBoard()
    await ask(call, { docId: 'spec-00001-b', question: 'why two gates?' })
    expect((await threadsOf(call, 'spec-00001-b'))[0]!.exchanges[0]!.outcome).toBe('running')

    // A crash leaves the file as it stands; the next boot is what reconciles it.
    const rebooted = boardOnRepo(repoRoot, docsDir)

    expect(rebooted.board.asks.read('spec-00001-b').threads[0]!.exchanges[0]!.outcome).toBe('failed')
  })

  // spec-00005-AC-5.4 — a normal shutdown stops the call and the question says so
  it('records a call the shutdown stopped as terminated, ready to be resent', async () => {
    const { call, board, repoRoot, docsDir } = askBoard()
    await ask(call, { docId: 'spec-00001-b', question: 'why two gates?' })

    await board.shutdown()

    const rebooted = boardOnRepo(repoRoot, docsDir)
    const [thread] = rebooted.board.asks.read('spec-00001-b').threads
    expect(thread!.exchanges).toHaveLength(1)
    expect(thread!.exchanges[0]!.outcome).toBe('terminated')
  })

  // spec-00005-AC-5.5 — the history entry of an ask: the metadata, and the
  // captured answer standing in for a transcript there is none of
  it('writes a history entry whose transcript is the captured answer', async () => {
    const { call, board, end } = askBoard()
    const { body } = await ask(call, { docId: 'spec-00001-b', question: 'why two gates?' })
    end(0, { stdout: ANSWER('because they are cheap to check') })
    await board.sessions.whenFinished(body.sessionId)

    const { body: entry } = await call('GET', `/api/sessions/history/${body.sessionId}`)

    expect(entry.meta).toMatchObject({ kind: 'ask', docId: 'spec-00001-b', status: 'exited', exitCode: 0 })
    expect(entry.transcript).toBe('because they are cheap to check')
  })

  // spec-00005-AC-6.1 — a running advance on that document refuses no ask
  it('starts a call on a document an advance session is running on', async () => {
    const { call } = askBoard()
    await call('POST', '/api/sessions', { sourceId: 'spec-00001-b', targetType: 'plan' })

    const { status, body } = await ask(call, { docId: 'spec-00001-b', question: 'why two gates?' })

    expect(status).toBe(200)
    expect(body.threadId).toBe('t-1')
  })

  // spec-00005-AC-6.2 — and the other direction: a running ask occupies nothing
  it('starts an advance on a document a call is running on', async () => {
    const { call, board } = askBoard()
    await ask(call, { docId: 'spec-00001-b', question: 'why two gates?' })

    const { status } = await call('POST', '/api/sessions', { sourceId: 'spec-00001-b', targetType: 'plan' })

    expect(status).toBe(200)
    expect(board.sessions.list().map((session) => [session.kind, session.status])).toEqual([
      ['ask', 'running'],
      ['advance', 'running'],
    ])
  })

  // spec-00005-AC-6.3 — questions run in parallel; only follow-ups are serial
  it('runs two threads of the same document at once', async () => {
    const { call, board, spawned } = askBoard()
    await ask(call, { docId: 'spec-00001-b', question: 'why two gates?' })

    const { status, body } = await ask(call, { docId: 'spec-00001-b', question: 'and the ordering?' })

    expect(status).toBe(200)
    expect(body.threadId).toBe('t-2')
    expect(spawned).toHaveLength(2)
    expect(board.sessions.list().every((session) => session.status === 'running')).toBe(true)
  })

  // spec-00005-AC-6.4 — the cap counts asks, and a refused submit writes nothing
  it('refuses a call at the cap and appends nothing to the list', async () => {
    const { call, spawned } = askBoard(TREE, { maxSessions: 1 })
    await call('POST', '/api/sessions', { sourceId: 'prd-00001-p', targetType: 'spec' })

    const { status, body } = await ask(call, { docId: 'spec-00001-b', question: 'why two gates?' })

    expect(status).toBe(409)
    expect(body.reason).toBe('cap-reached')
    expect(spawned).toEqual([])
    expect(await threadsOf(call, 'spec-00001-b')).toEqual([])
  })

  /**
   * spec-00005-AC-6.5 — an ask enters neither path of the waiting judgment: a
   * headless call has no interactive input to be waiting for, and its silence is
   * it thinking (spec-00003-FR-6 as spec-00005-FR-6 amends it).
   */
  it('never reads a silent call as waiting on the user', async () => {
    const { call, board } = askBoard(TREE, { awaitThresholdMs: 50 })
    await ask(call, { docId: 'spec-00001-b', question: 'why two gates?' })

    await new Promise((resolve) => setTimeout(resolve, 300))

    expect(board.sessions.list()[0]!.status).toBe('running')
    expect(board.sessions.list()[0]!.awaiting).toBeFalsy()
    expect((await call('GET', '/api/sessions')).body.sessions[0].awaiting).toBeFalsy()
  })

  // spec-00005-AC-7.1 — one call at a time per thread, and the refusal says so
  it('refuses a second submit on a thread whose call is running', async () => {
    const { call, spawned } = askBoard()
    await ask(call, { docId: 'spec-00001-b', question: 'why two gates?' })

    const { status, body } = await ask(call, { docId: 'spec-00001-b', question: 'again', threadId: 't-1' })

    expect(status).toBe(409)
    expect(body.reason).toBe('thread-busy')
    expect(spawned).toHaveLength(1)
    expect((await threadsOf(call, 'spec-00001-b'))[0]!.exchanges).toHaveLength(1)
  })

  // spec-00005-AC-7.2 — an anomalous document offers no entry and takes no request
  it('refuses a call on an anomalous document and starts nothing', async () => {
    const { call, board, spawned } = askBoard({ 'spec/broken.md': BROKEN })

    const { status, body } = await ask(call, { docId: 'nope', question: 'what is this?' })

    expect(status).toBe(422)
    expect(body.error).toMatch(/front matter problems/)
    expect(spawned).toEqual([])
    expect(board.sessions.list()).toEqual([])
  })

  // spec-00005-AC-7.4 — with no headless form declared anywhere, there is nothing to ask with
  it('refuses a call when no agent declares a headless form', async () => {
    const { call, spawned } = askBoard(TREE, { agents: [{ name: 'plain', command: 'node', args: [], cwd: 'docs' }] })

    const { status, body } = await ask(call, { docId: 'spec-00001-b', question: 'why two gates?' })

    expect(status).toBe(422)
    expect(body.error).toMatch(/no agent in the flow config declares a headless form/)
    expect(spawned).toEqual([])
  })

  /**
   * spec-00005-AC-7.5 — a call that ended non-zero leaves its question failed and
   * resendable, the rest of the thread untouched; the resend rewrites that one
   * question where it stands rather than adding another (design-00001 §10.2).
   */
  it('marks a call that failed, keeps the rest of the thread, and resends into a new call', async () => {
    const { call, board, spawned, end } = askBoard()
    const first = await ask(call, { docId: 'spec-00001-b', question: 'why two gates?' })
    end(0, { stdout: ANSWER('because they are cheap') })
    await board.sessions.whenFinished(first.body.sessionId)
    const followUp = await ask(call, { docId: 'spec-00001-b', question: 'and the third?', threadId: 't-1' })
    end(1, { exitCode: 1, stderr: 'the CLI gave up' })
    await board.sessions.whenFinished(followUp.body.sessionId)

    const failed = (await threadsOf(call, 'spec-00001-b'))[0]!
    const { status } = await ask(call, {
      docId: 'spec-00001-b',
      question: 'and the third?',
      threadId: 't-1',
      resend: true,
    })

    expect(failed.exchanges[1]!.outcome).toBe('failed')
    // The continuation is marked rather than swapped for a fresh conversation.
    expect(failed.resumeInvalid).toBe(true)
    expect(failed.exchanges[0]).toMatchObject({ answer: 'because they are cheap', outcome: 'answered' })
    expect(status).toBe(200)
    expect(spawned).toHaveLength(3)
    // The resend rewrote the question it resent; the list grew by no retry.
    expect((await threadsOf(call, 'spec-00001-b'))[0]!.exchanges).toHaveLength(2)
  })

  /**
   * Finding 6 — a follow-up after a question that failed is a *new* question,
   * and appends. Only a resend rewrites, and only the caller knows which was
   * meant: guessing from the record would file the new question over the old
   * one, losing what was asked (spec-00005-FR-3's «the list grows by a
   * question»).
   */
  it('appends a new follow-up after a question that failed, rather than overwriting it', async () => {
    const { call, board, end } = askBoard()
    const first = await ask(call, { docId: 'spec-00001-b', question: 'why two gates?' })
    end(0, { exitCode: 1, stderr: 'the CLI gave up' })
    await board.sessions.whenFinished(first.body.sessionId)

    await ask(call, { docId: 'spec-00001-b', question: 'a different question', threadId: 't-1' })

    const [thread] = await threadsOf(call, 'spec-00001-b')
    expect(thread!.exchanges.map((exchange) => [exchange.question, exchange.outcome])).toEqual([
      ['why two gates?', 'failed'],
      ['a different question', 'running'],
    ])
  })

  // Finding 6 — a resend needs something to resend; the answered question is not it
  it('refuses a resend on a thread whose last question was answered', async () => {
    const { call, board, spawned, end } = askBoard()
    const first = await ask(call, { docId: 'spec-00001-b', question: 'why two gates?' })
    end(0, { stdout: ANSWER('because they are cheap') })
    await board.sessions.whenFinished(first.body.sessionId)

    const { status, body } = await ask(call, {
      docId: 'spec-00001-b',
      question: 'why two gates?',
      threadId: 't-1',
      resend: true,
    })

    expect(status).toBe(422)
    expect(body.error).toMatch(/no unanswered question to resend/)
    expect(spawned).toHaveLength(1)
    expect((await threadsOf(call, 'spec-00001-b'))[0]!.exchanges).toHaveLength(1)
  })

  // Finding 6 — a resend into a thread mid-run is still the thread's serial rule
  it('refuses a resend while that thread has a call running', async () => {
    const { call, spawned } = askBoard()
    await ask(call, { docId: 'spec-00001-b', question: 'why two gates?' })

    const { status, body } = await ask(call, {
      docId: 'spec-00001-b',
      question: 'why two gates?',
      threadId: 't-1',
      resend: true,
    })

    expect(status).toBe(409)
    expect(body.reason).toBe('thread-busy')
    expect(spawned).toHaveLength(1)
  })

  /**
   * Finding 7 — the «continuation is gone» mark is about a continuation the CLI
   * refused, and a call the user stopped says nothing about that. Per
   * design-00001 §10.2 the criterion is failure alone.
   */
  it('leaves the continuation unmarked when the user stops a resumed call', async () => {
    const { call, board, end } = askBoard()
    const first = await ask(call, { docId: 'spec-00001-b', question: 'why two gates?' })
    end(0, { stdout: ANSWER('because they are cheap') })
    await board.sessions.whenFinished(first.body.sessionId)
    const followUp = await ask(call, { docId: 'spec-00001-b', question: 'and the third?', threadId: 't-1' })

    await board.sessions.terminate(followUp.body.sessionId)

    const [thread] = await threadsOf(call, 'spec-00001-b')
    expect(thread!.exchanges[1]!.outcome).toBe('terminated')
    expect(thread!.resumeInvalid).toBeUndefined()
    expect(thread!.resumeId).toBe('cli-1')
  })

  /**
   * Finding 8 — the latest id wins. A CLI is free to hand back a new id for each
   * resumed print run, and keeping the first would send every later follow-up
   * back to a conversation that has since moved on.
   */
  it('takes the resume id of the latest answered call', async () => {
    const { call, board, spawned, end } = askBoard()
    const first = await ask(call, { docId: 'spec-00001-b', question: 'why two gates?' })
    end(0, { stdout: ANSWER('because they are cheap', 'cli-1') })
    await board.sessions.whenFinished(first.body.sessionId)
    const followUp = await ask(call, { docId: 'spec-00001-b', question: 'and the third?', threadId: 't-1' })

    end(1, { stdout: ANSWER('it is the same gate twice', 'cli-2') })
    await board.sessions.whenFinished(followUp.body.sessionId)
    await ask(call, { docId: 'spec-00001-b', question: 'and again?', threadId: 't-1' })

    expect((await threadsOf(call, 'spec-00001-b'))[0]!.resumeId).toBe('cli-2')
    expect(spawned[2]!.args).toContain('cli-2')
    expect(spawned[2]!.args).not.toContain('cli-1')
  })

  /**
   * Finding 4 — a list that cannot be read must not be written over: read as
   * empty and written back, it would erase every thread the document has, and
   * the list is the only copy (spec-00005-FR-5).
   */
  it('refuses a submit over an unreadable list, leaving the file as it was', async () => {
    const { call, repoRoot, spawned } = askBoard()
    const path = join(repoRoot, '.whiteboard/asks/spec-00001-b.json')
    mkdirSync(join(repoRoot, '.whiteboard/asks'), { recursive: true })
    writeFileSync(path, '{ truncated mid-w')

    const { status, body } = await ask(call, { docId: 'spec-00001-b', question: 'why two gates?' })

    expect(status).toBe(422)
    expect(body.error).toMatch(/cannot be read, so nothing may be written over it/)
    expect(spawned).toEqual([])
    expect(readFileSync(path, 'utf8')).toBe('{ truncated mid-w')
    // The reading path stays forgiving: one broken file costs its list, not the board.
    expect((await call('GET', '/api/asks/spec-00001-b')).body).toEqual({ threads: [] })
  })

  /**
   * Finding 4 — the wrap-up may not write over a list it cannot read either.
   * This one call's record is lost; every other thread on that file keeps its
   * own, which is the trade the other way round.
   */
  it('skips landing the answer when the list has become unreadable', async () => {
    const { call, board, repoRoot, end } = askBoard()
    const { body } = await ask(call, { docId: 'spec-00001-b', question: 'why two gates?' })
    const path = join(repoRoot, '.whiteboard/asks/spec-00001-b.json')
    rmSync(path)
    mkdirSync(path, { recursive: true })

    end(0, { stdout: ANSWER('because they are cheap') })
    await board.sessions.whenFinished(body.sessionId)

    // The call still wrapped up, and nothing was written over the file.
    expect(board.sessions.list()[0]!.status).toBe('exited')
    expect(board.sessions.list()[0]!.outcome).toMatchObject({ docId: 'spec-00001-b', committed: false })
    expect(statSync(path).isDirectory()).toBe(true)
  })

  // spec-00005-FR-7 at the request boundary: a body that is no ask at all
  it('refuses a request whose question, thread or resend is not what it has to be', async () => {
    const { call, spawned } = askBoard()

    for (const [body, match] of [
      [{ docId: 'spec-00001-b' }, /needs a question/],
      [{ docId: 'spec-00001-b', question: '   ' }, /needs a question/],
      [{ docId: 'spec-00001-b', question: 'why?', threadId: 7 }, /threadId must name a thread/],
      [{ docId: 'spec-00001-b', question: 'why?', resend: 'yes' }, /resend says whether/],
    ] as const) {
      const answer = await call('POST', '/api/sessions/ask', body)
      expect(answer.status).toBe(422)
      expect(answer.body.error).toMatch(match)
    }
    expect(spawned).toEqual([])
  })

  // Finding 4 — a file the reconciliation cannot read is left for a person, not rewritten
  it('leaves an unreadable list alone at boot instead of rewriting it', async () => {
    const { repoRoot, docsDir } = askBoard()
    const path = join(repoRoot, '.whiteboard/asks/spec-00001-b.json')
    mkdirSync(join(repoRoot, '.whiteboard/asks'), { recursive: true })
    writeFileSync(path, 'not a list at all')

    boardOnRepo(repoRoot, docsDir)

    expect(readFileSync(path, 'utf8')).toBe('not a list at all')
  })

  /**
   * Finding 1 — the slot is taken before the record is written, so a write that
   * fails leaves a session admitted with no process to come. Left as it is, it
   * would hold its slot for good and a shutdown would wait on it for ever.
   */
  it('gives up the admitted session when the list cannot be written', async () => {
    const { call, board, repoRoot, spawned } = askBoard()
    // A directory where the file has to go: the write fails, the read does not.
    mkdirSync(join(repoRoot, '.whiteboard/asks/spec-00001-b.json.tmp'), { recursive: true })

    const { status } = await ask(call, { docId: 'spec-00001-b', question: 'why two gates?' })

    expect(status).toBe(500)
    expect(spawned).toEqual([])
    // Listed as what happened, running nothing, and holding no slot.
    expect(board.sessions.list().map((session) => session.status)).toEqual(['failed'])
    await expect(board.shutdown()).resolves.toBeUndefined()
  })

  /**
   * Finding 1 — a spawn seam that throws is a call that never ran, and a call
   * that never ran is a failed one: it goes down the ordinary ask exit path so
   * its question lands `failed` rather than staying `running` for ever.
   */
  it('lands the question as failed when the spawn seam itself throws', async () => {
    const { repoRoot, docsDir } = makeRepo(TREE)
    const throwing: SpawnHeadless = () => {
      throw new Error('agent command not found on PATH: nope')
    }
    const { call, board } = boardOnRepo(repoRoot, docsDir, testConfig(), undefined, undefined, throwing)

    const { body } = await ask(call, { docId: 'spec-00001-b', question: 'why two gates?' })
    await board.sessions.whenFinished(body.sessionId)

    const [thread] = await threadsOf(call, 'spec-00001-b')
    expect(thread!.exchanges[0]!.outcome).toBe('failed')
    expect(board.sessions.list()[0]!.status).toBe('exited')
    expect((await call('GET', `/api/sessions/history/${body.sessionId}`)).body.transcript).toContain(
      'could not start the agent',
    )
  })

  /**
   * Finding 3 — landing the answer is a disk write, and a disk that will not
   * take it costs the user that record and nothing else. Left to reject it would
   * skip the wrap-up hook, so no board would ever hear the call ended, and then
   * bring the process down as an unhandled rejection.
   */
  it('still finishes the wrap-up when the answer cannot be landed on the thread', async () => {
    const { call, board, repoRoot, end } = askBoard()
    const { body } = await ask(call, { docId: 'spec-00001-b', question: 'why two gates?' })
    // The list still reads; it is the write the wrap-up has to make that is
    // wedged, which is the failure this is about.
    mkdirSync(join(repoRoot, '.whiteboard/asks/spec-00001-b.json.tmp'), { recursive: true })

    end(0, { stdout: ANSWER('because they are cheap') })
    await board.sessions.whenFinished(body.sessionId)

    const session = board.sessions.list()[0]!
    expect(session.status).toBe('exited')
    // The hook ran: an ask commits nothing, and says so.
    expect(session.outcome).toMatchObject({ docId: 'spec-00001-b', committed: false })
    expect(session.historyError).toBeDefined()
  })

  /**
   * spec-00005-AC-7.7 — an ask runs no pty, so terminal attach, input and resize
   * are three refusals of the one thing that is not there. The socket carries all
   * three, and it is closed on the first (design-00001 §10.3).
   */
  it('refuses terminal attach, input and resize on a call', async () => {
    const { call, board, port } = askBoard()
    const { body } = await ask(call, { docId: 'spec-00001-b', question: 'why two gates?' })

    const socket = new WebSocket(`ws://127.0.0.1:${port}/api/terminal?sessionId=${body.sessionId}`)
    const closed = new Promise<void>((resolve) => socket.addEventListener('close', () => resolve()))

    await closed
    expect(() => board.sessions.attach(body.sessionId, () => {})).toThrowError(/has no terminal/)
    expect(() => board.sessions.write(body.sessionId, 'hello')).toThrowError(/has no terminal/)
    expect(() => board.sessions.resize(body.sessionId, 80, 24)).toThrowError(/has no terminal/)
  })

  /**
   * The list is addressed by document id and that id is the file's whole name,
   * so the shape is checked before it is ever used as a path — the same guard
   * the session history reads its filenames through (design-00001 §7).
   */
  it('serves an empty list for a document with none, and for an id that is no filename', async () => {
    const { call } = askBoard()

    expect((await call('GET', '/api/asks/spec-00001-b')).body).toEqual({ threads: [] })
    expect((await call('GET', `/api/asks/${encodeURIComponent('../sessions/anything')}`)).body).toEqual({ threads: [] })
  })

  it('refuses a follow-up naming a thread the list does not hold', async () => {
    const { call, spawned } = askBoard()

    const { status, body } = await ask(call, { docId: 'spec-00001-b', question: 'go on', threadId: 't-9' })

    expect(status).toBe(422)
    expect(body.error).toMatch(/is not a thread of the ask list/)
    expect(spawned).toEqual([])
  })

  // spec-00005-AC-8.2 — both declared forms run as declared
  it('runs the declared first form and then the declared resume form', async () => {
    const { call, board, spawned, end } = askBoard()
    const first = await ask(call, { docId: 'spec-00001-b', question: 'why two gates?' })
    end(0, { stdout: ANSWER('because they are cheap', 'cli-9') })
    await board.sessions.whenFinished(first.body.sessionId)

    await ask(call, { docId: 'spec-00001-b', question: 'and the third?', threadId: 't-1' })

    const declared = testConfig().agents[0]!.headless!
    expect(spawned[0]!.args).toHaveLength(declared.first.length)
    expect(spawned[1]!.args).toHaveLength(declared.resume.length)
    expect(spawned[1]!.args).toContain('cli-9')
    expect(spawned[0]!.args).not.toContain('cli-9')
  })

  /**
   * spec-00005-FR-7 — a call can exit **zero** and still have answered nothing:
   * the CLI reports its own failure in the field the answer would have been
   * (design-00001 §10.1). The question then says why it has no answer, while the
   * process's own story stays what it was — it did exit zero, and pretending
   * otherwise would make the panel lie about the process (design-00001 §10.3).
   */
  it('files the CLI’s own error as the question’s reason and leaves the exit honest', async () => {
    const { call, board, end } = askBoard()
    const first = await ask(call, { docId: 'spec-00001-b', question: 'why two gates?' })

    end(0, { stdout: JSON.stringify({ result: 'Credit balance too low', session_id: 'cli-1', is_error: true }) })
    await board.sessions.whenFinished(first.body.sessionId)

    const [thread] = await threadsOf(call, 'spec-00001-b')
    expect(thread!.exchanges[0]).toMatchObject({ outcome: 'failed', reason: 'Credit balance too low' })
    expect(thread!.exchanges[0]!.answer).toBeUndefined()
    expect(board.sessions.list()[0]).toMatchObject({ status: 'exited', exitCode: 0 })
  })

  /**
   * The rest of the same ordering: what the CLI said last on stderr, and — when
   * it died saying nothing at all — the exit code, which is the only thing there
   * is to say (design-00001 §10.3).
   */
  it('falls back to the last line of stderr, and to the exit code when there is none', async () => {
    const { call, board, end } = askBoard()
    const first = await ask(call, { docId: 'spec-00001-b', question: 'why two gates?' })
    end(0, { exitCode: 1, stdout: 'not json at all', stderr: 'connecting…\nauth: no such profile\n' })
    await board.sessions.whenFinished(first.body.sessionId)
    const silent = await ask(call, { docId: 'spec-00001-b', question: 'and the third?' })
    end(1, { exitCode: 2 })
    await board.sessions.whenFinished(silent.body.sessionId)

    const threads = await threadsOf(call, 'spec-00001-b')
    expect(threads[0]!.exchanges[0]).toMatchObject({ outcome: 'failed', reason: 'auth: no such profile' })
    expect(threads[1]!.exchanges[0]).toMatchObject({ outcome: 'failed', reason: 'exit 2' })
  })

  /**
   * A question that was answered carries no reason from the failure before it:
   * the reason belongs to the landing that wrote it (design-00001 §10.2 — the
   * resend rewrites the question where it stands).
   */
  it('clears the reason when the resend is answered', async () => {
    const { call, board, end } = askBoard()
    const first = await ask(call, { docId: 'spec-00001-b', question: 'why two gates?' })
    end(0, { exitCode: 2 })
    await board.sessions.whenFinished(first.body.sessionId)
    const again = await ask(call, { docId: 'spec-00001-b', question: 'why two gates?', threadId: 't-1', resend: true })
    end(1, { stdout: ANSWER('because they are cheap') })
    await board.sessions.whenFinished(again.body.sessionId)

    const [thread] = await threadsOf(call, 'spec-00001-b')
    expect(thread!.exchanges[0]).toMatchObject({ outcome: 'answered', answer: 'because they are cheap' })
    expect(thread!.exchanges[0]!.reason).toBeUndefined()
  })

  /**
   * design-00001 §10.2 写序 — the record is on disk before the process exists, so
   * anything that throws past it would leave the question `running` with nothing
   * to answer it: the thread refuses every submit while a question of its own is
   * running (spec-00005-AC-7.1), so it would be shut until a restart reconciled
   * it (AC-5.3). The rollback lands that question `failed` with its reason
   * instead, and it takes a resend at once (spec-00005-FR-7).
   */
  it('lands the question failed when the launch past the record throws', async () => {
    const { call, board, spawned } = askBoard()
    const launch = board.sessions.launch.bind(board.sessions)
    board.sessions.launch = () => {
      throw new Error('the spawn seam gave out')
    }

    const refused = await ask(call, { docId: 'spec-00001-b', question: 'why two gates?' })

    expect(refused.status).toBeGreaterThanOrEqual(400)
    const [thread] = await threadsOf(call, 'spec-00001-b')
    expect(thread!.exchanges[0]).toMatchObject({ outcome: 'failed', reason: 'the spawn seam gave out' })
    // The slot went back with it, and the continuation is not in doubt: no CLI
    // was ever asked, so nothing refused a resume id.
    expect(board.sessions.list()[0]!.status).toBe('failed')
    expect(thread!.resumeInvalid).toBeUndefined()
    expect(spawned).toEqual([])

    board.sessions.launch = launch
    const resent = await ask(call, {
      docId: 'spec-00001-b',
      question: 'why two gates?',
      threadId: 't-1',
      resend: true,
    })

    expect(resent.status).toBe(200)
    expect(spawned).toHaveLength(1)
  })

  /**
   * The other end of the same 写序 window: a stop that lands after the admission
   * but before the process exists left only its mark, and the mark is honoured
   * the moment there is something to signal — otherwise the call runs on with
   * its own session already reading `terminated` (spec-00005-AC-7.6).
   */
  it('kills a call whose stop landed before its process existed', async () => {
    const { board, spawned } = askBoard()
    const info = board.sessions.start(board.docs.askPlan('spec-00001-b', 'why two gates?', { id: 't-1' }))

    const stopped = board.sessions.terminate(info.id)
    board.sessions.launch(info.id)

    expect((await stopped).status).toBe('terminated')
    expect(spawned).toHaveLength(1)
  })
})

/**
 * The fifth session kind over the HTTP surface (spec-00006-FR-1, FR-2, FR-5 and
 * FR-10, design-00001 §11.2): one entry in two forms — a document that is already
 * on disk, and one this very request files first.
 */
describe('cowrite sessions', () => {
  const DRAFT_INTEGRATION = doc({ id: 'integration-00001-cli', type: 'integration', status: 'draft' }, '# CLI\n')
  const IDEA_TEMPLATE = '---\nid: idea-00000-slug\ntype: idea\nstatus: draft\n---\n\n# Title\n'
  const HOLD = ['-e', 'setTimeout(() => {}, 5000)']

  /** A stand-in pty that records what was spawned and everything written into it. */
  function pens() {
    const spawned: Array<{ command: string; args: string[]; cwd: string }> = []
    const written: string[] = []
    const exits: Array<(exitCode: number) => void> = []
    const spawn: SpawnPty = (command, args, cwd) => {
      spawned.push({ command, args, cwd })
      const listeners: Array<(event: { exitCode: number }) => void> = []
      let gone = false
      const end = (exitCode: number) => {
        if (gone) return
        gone = true
        for (const listener of listeners) listener({ exitCode })
      }
      exits.push(end)
      return {
        onData: () => {},
        onExit: (listener) => void listeners.push(listener),
        write: (data) => void written.push(data),
        resize: () => {},
        kill: () => end(0),
      }
    }
    return { spawn, spawned, written, exit: (index = 0, exitCode = 0) => exits[index]!(exitCode) }
  }

  /** A board whose flow config declares the types a cowrite round is about. */
  function cowriteBoard(
    files: Record<string, string>,
    options: { args?: string[]; spawn?: SpawnPty; maxSessions?: number; second?: string[] } = {},
  ) {
    const { repoRoot, docsDir } = makeRepo(files)
    const config = testConfig()
    Object.assign(config.types, { reference: 'living', integration: 'living', report: 'living' })
    config.agents[0] = { ...config.agents[0]!, args: options.args ?? ['-e', ''] }
    if (options.second) config.agents.push({ name: 'second', command: 'node', args: options.second, cwd: 'docs' })
    if (options.maxSessions !== undefined) config.maxSessions = options.maxSessions
    return boardOnRepo(repoRoot, docsDir, config, options.spawn ?? spawnPty)
  }

  // spec-00006-AC-1.1 — the session starts, and the instruction carries the scope
  it('starts a cowrite session on a draft integration document and tells it what it may write', async () => {
    const { spawn, written } = pens()
    const { call, board } = cowriteBoard({ 'integration/cli.md': DRAFT_INTEGRATION }, { spawn })

    const { status, body } = await call('POST', '/api/sessions/cowrite', { docId: 'integration-00001-cli' })

    expect(status).toBe(200)
    expect(body.docId).toBe('integration-00001-cli')
    expect(board.sessions.latest()).toMatchObject({
      id: body.sessionId,
      kind: 'cowrite',
      sourceId: 'integration-00001-cli',
      status: 'running',
    })
    expect(written[0]).toContain('integration/cli.md')
    expect(written[0]).toContain('integration/README.md')
    expect(written[0]).toContain('never its front matter id or status line')
    expect(written[0]).toContain('reference/TEMPLATE.md')
  })

  // spec-00006-AC-1.3 — the agent choice of spec-00001-FR-55, on the fifth kind
  it('runs the second configured agent when the request names it', async () => {
    const { spawn, spawned } = pens()
    const { call } = cowriteBoard({ 'integration/cli.md': DRAFT_INTEGRATION }, { spawn, second: ['--second'] })

    const { status } = await call('POST', '/api/sessions/cowrite', {
      docId: 'integration-00001-cli',
      agent: 'second',
    })

    expect(status).toBe(200)
    expect(spawned[0]!.args).toEqual(['--second'])
  })

  it('answers 422 for an unknown agent and starts nothing', async () => {
    const { call, board } = cowriteBoard({ 'integration/cli.md': DRAFT_INTEGRATION })

    const { status } = await call('POST', '/api/sessions/cowrite', { docId: 'integration-00001-cli', agent: 'nope' })

    expect(status).toBe(422)
    expect(board.sessions.latest()).toBeNull()
  })

  // spec-00006-AC-3.1 and AC-3.2 over the entry: the materials reach the task input
  // rule-00001-AC-28.3
  it('carries every kind of material into the first task input', async () => {
    const { spawn, written } = pens()
    const { call } = cowriteBoard({ 'integration/cli.md': DRAFT_INTEGRATION, 'idea/a.md': DRAFT_IDEA }, { spawn })

    await call('POST', '/api/sessions/cowrite', {
      docId: 'integration-00001-cli',
      materials: {
        text: 'the owner pasted this',
        docIds: ['idea-00001-x'],
        paths: ['/Users/owner/case.md'],
        urls: ['https://example.test/case'],
      },
    })

    expect(written[0]).toContain('the owner pasted this')
    expect(written[0]).toContain('idea-00001-x at idea/a.md')
    expect(written[0]).toContain('/Users/owner/case.md')
    expect(written[0]).toContain('https://example.test/case')
  })

  // spec-00006-AC-3.3
  it('starts the session with no materials segment when none was given', async () => {
    const { spawn, written } = pens()
    const { call } = cowriteBoard({ 'integration/cli.md': DRAFT_INTEGRATION }, { spawn })

    expect((await call('POST', '/api/sessions/cowrite', { docId: 'integration-00001-cli' })).status).toBe(200)
    expect(written[0]).not.toContain('The materials the owner gave you')
  })

  it('answers 422 for a request that names neither a document nor a create, or both', async () => {
    const { call, board } = cowriteBoard({ 'integration/cli.md': DRAFT_INTEGRATION })

    for (const request of [{}, { docId: 'integration-00001-cli', create: { type: 'idea', slug: 'both' } }]) {
      const { status, body } = await call('POST', '/api/sessions/cowrite', request)
      expect(status).toBe(422)
      expect(body.error).toMatch(/either the docId/)
    }
    expect(board.sessions.latest()).toBeNull()
  })

  it('answers 422 for materials that are not text and lists of strings', async () => {
    const { call } = cowriteBoard({ 'integration/cli.md': DRAFT_INTEGRATION })

    for (const materials of [{ text: 7 }, { urls: 'https://example.test' }, { docIds: [7] }]) {
      expect((await call('POST', '/api/sessions/cowrite', { docId: 'integration-00001-cli', materials })).status).toBe(422)
    }
  })

  it('answers 422 for a create that names no type and slug, and for a docId that is not one', async () => {
    const { call } = cowriteBoard({})
    expect((await call('POST', '/api/sessions/cowrite', { create: {} })).status).toBe(422)
    expect((await call('POST', '/api/sessions/cowrite', { docId: 7 })).status).toBe(422)
  })

  /**
   * The other half of spec-00006-FR-2's all or nothing: a write that fails leaves
   * no half-filed document behind, and the slot it had taken goes straight back
   * (spec-00003-AC-3.7).
   */
  it('files nothing and holds no slot when the document cannot be written to disk', async () => {
    const { call, board, docsDir } = cowriteBoard({ 'idea/TEMPLATE.md': IDEA_TEMPLATE })
    chmodSync(join(docsDir, 'idea'), 0o500)

    const { status } = await call('POST', '/api/sessions/cowrite', { create: { type: 'idea', slug: 'co-written' } })

    chmodSync(join(docsDir, 'idea'), 0o700)
    expect(status).toBe(500)
    expect(existsSync(join(docsDir, 'idea/idea-00001-co-written.md'))).toBe(false)
    expect(board.sessions.list()).toMatchObject([{ kind: 'cowrite', status: 'failed' }])
  })

  // spec-00006-AC-9.1 over the entry — the refusal is the reception's, not the UI's
  it('answers 422 for an active document and starts nothing', async () => {
    const { call, board } = cowriteBoard({ 'idea/a.md': ACTIVE_IDEA })

    const { status, body } = await call('POST', '/api/sessions/cowrite', { docId: 'idea-00001-x' })

    expect(status).toBe(422)
    expect(body.error).toMatch(/rule-00001-BR-29/)
    expect(board.sessions.latest()).toBeNull()
  })

  // spec-00006-AC-2.1 — confirm and the document is filed, committed, and cowritten
  it('files the document from its template, commits it, and starts the session on it', async () => {
    const { spawn, written } = pens()
    const { call, board, repoRoot, docsDir } = cowriteBoard({ 'idea/TEMPLATE.md': IDEA_TEMPLATE }, { spawn })

    const { status, body } = await call('POST', '/api/sessions/cowrite', {
      create: { type: 'idea', slug: 'co-written' },
    })

    expect(status).toBe(200)
    expect(body.docId).toBe('idea-00001-co-written')
    expect(readFileSync(join(docsDir, 'idea/idea-00001-co-written.md'), 'utf8')).toContain('id: idea-00001-co-written')
    expect(lastCommitMessage(repoRoot)).toBe('wb(create): idea-00001-co-written')
    expect(board.sessions.latest()).toMatchObject({ kind: 'cowrite', sourceId: 'idea-00001-co-written' })
    expect(written[0]).toContain('idea/idea-00001-co-written.md')
  })

  // spec-00006-AC-2.2, AC-2.3 and AC-2.4 — any one of the three refuses the whole thing
  it('files nothing and starts nothing when the slug, the type or the id refuses the create', async () => {
    const { call, board, repoRoot, docsDir } = cowriteBoard({
      'idea/TEMPLATE.md': IDEA_TEMPLATE,
      'idea/idea-00001-taken.md': '# no front matter at all\n',
    })
    const commits = commitCount(repoRoot)

    for (const [create, expected] of [
      [{ type: 'idea', slug: 'Not A Slug' }, 422],
      [{ type: 'spec', slug: 'not-an-entry-type' }, 422],
      [{ type: 'idea', slug: 'taken' }, 409],
    ] as const) {
      expect((await call('POST', '/api/sessions/cowrite', { create })).status).toBe(expected)
    }
    expect(board.sessions.list()).toEqual([])
    expect(commitCount(repoRoot)).toBe(commits)
    expect(existsSync(join(docsDir, 'idea/idea-00002-taken.md'))).toBe(false)
  })

  /**
   * The create's **commit** failing is not the same as its write failing
   * (spec-00006-FR-2 by way of spec-00001-FR-20): the document is on disk, so it
   * can be cowritten — the session goes ahead and the error rides along.
   */
  it('keeps the document and starts the session when the create commit fails', async () => {
    const { spawn } = pens()
    const { call, board, repoRoot, docsDir } = cowriteBoard({ 'idea/TEMPLATE.md': IDEA_TEMPLATE }, { spawn })
    // A lock left behind is the ordinary way a git write fails from underneath the
    // board: no index can be taken while it is there.
    const lock = join(repoRoot, '.git/index.lock')
    writeFileSync(lock, '')

    const { status, body } = await call('POST', '/api/sessions/cowrite', {
      create: { type: 'idea', slug: 'co-written' },
    })

    rmSync(lock)
    expect(status).toBe(200)
    expect(body.error).toMatch(/index\.lock/)
    expect(existsSync(join(docsDir, 'idea/idea-00001-co-written.md'))).toBe(true)
    expect(board.sessions.latest()).toMatchObject({ kind: 'cowrite', status: 'running' })
  })

  // spec-00006-AC-2.5 — the blank mode is untouched: the template prefills the
  // editor, the save files the document, and no session starts
  it('leaves the blank create path exactly as it was', async () => {
    const { call, board, repoRoot } = cowriteBoard({ 'idea/TEMPLATE.md': IDEA_TEMPLATE })

    const prefill = await call('GET', '/api/create?type=idea')
    expect(prefill.body.idPrefix).toBe('idea-00001-')
    const created = await call('POST', '/api/docs', {
      id: 'idea-00001-by-hand',
      content: IDEA_TEMPLATE.replace('idea-00000-slug', 'idea-00001-by-hand'),
    })

    expect(created.status).toBe(201)
    expect(lastCommitMessage(repoRoot)).toBe('wb(create): idea-00001-by-hand')
    expect(board.sessions.list()).toEqual([])
  })

  // spec-00006-AC-2.6 — the cap refuses before anything is filed
  it('files no document when the session cap refuses the create', async () => {
    const { call, board, repoRoot, docsDir } = cowriteBoard(
      { 'idea/TEMPLATE.md': IDEA_TEMPLATE, 'spec/b.md': doc({ id: 'spec-00001-b', type: 'spec', status: 'draft' }) },
      { args: HOLD, maxSessions: 1 },
    )
    await call('POST', '/api/sessions/audit', { docId: 'spec-00001-b' })
    const commits = commitCount(repoRoot)

    const { status, body } = await call('POST', '/api/sessions/cowrite', {
      create: { type: 'idea', slug: 'co-written' },
    })

    expect(status).toBe(409)
    expect(body.reason).toBe('cap-reached')
    expect(existsSync(join(docsDir, 'idea/idea-00001-co-written.md'))).toBe(false)
    expect(commitCount(repoRoot)).toBe(commits)
    expect(board.sessions.list()).toHaveLength(1)
  })

  // spec-00006-AC-2.7 — the refused create held no slot of its own
  it('admits the same create once the running session has ended', async () => {
    const { call, board, docsDir } = cowriteBoard(
      { 'idea/TEMPLATE.md': IDEA_TEMPLATE, 'spec/b.md': doc({ id: 'spec-00001-b', type: 'spec', status: 'draft' }) },
      { args: HOLD, maxSessions: 1 },
    )
    const { body: audit } = await call('POST', '/api/sessions/audit', { docId: 'spec-00001-b' })
    expect((await call('POST', '/api/sessions/cowrite', { create: { type: 'idea', slug: 'co-written' } })).status).toBe(409)

    await call('DELETE', `/api/sessions/${audit.id}`)
    const { status, body } = await call('POST', '/api/sessions/cowrite', { create: { type: 'idea', slug: 'co-written' } })

    expect(status).toBe(200)
    expect(body.docId).toBe('idea-00001-co-written')
    expect(existsSync(join(docsDir, 'idea/idea-00001-co-written.md'))).toBe(true)
    expect(board.sessions.latest()!.kind).toBe('cowrite')
  })

  // spec-00006-AC-10.1 and AC-10.3 — the status lock, and the editor bypass closed
  it('refuses a status change, an accept and an identity-moving save while the session runs', async () => {
    const { call } = cowriteBoard({ 'integration/cli.md': DRAFT_INTEGRATION }, { args: HOLD })
    await call('POST', '/api/sessions/cowrite', { docId: 'integration-00001-cli' })
    const { body: opened } = await call('GET', '/api/docs/integration-00001-cli')

    for (const [path, request] of [
      ['/api/docs/integration-00001-cli/status', { to: 'active' }],
      ['/api/docs/integration-00001-cli/review', { action: 'accept' }],
    ] as const) {
      const { status, body } = await call('POST', path, request)
      expect(status).toBe(409)
      expect(body.reason).toBe('doc-busy')
    }
    const save = await call('PUT', '/api/docs/integration-00001-cli', {
      content: opened.content.replace('status: draft', 'status: active'),
      baseHash: opened.hash,
    })

    expect(save.status).toBe(409)
    expect(save.body.reason).toBe('doc-busy')
  })

  // spec-00006-AC-10.2 — the lock is the session, so the gates rule as usual after it
  it('evaluates the review gate as usual once the cowrite session has ended', async () => {
    const { call, board } = cowriteBoard({ 'integration/cli.md': DRAFT_INTEGRATION })
    const { body: started } = await call('POST', '/api/sessions/cowrite', { docId: 'integration-00001-cli' })
    await vi.waitFor(() => expect(board.sessions.latest()!.status).toBe('exited'), SESSION_WAIT)
    await board.sessions.whenFinished(started.sessionId)

    const { status, body } = await call('POST', '/api/docs/integration-00001-cli/review', { action: 'accept' })

    expect(status).toBe(200)
    expect(body.status).toBe('active')
  })

  /**
   * spec-00006-AC-10.4 and AC-5.1: the body-only save lands as its own edit
   * commit, and the note it leaves rides ahead of the owner's next printable
   * frame (design-00001 §11.4). The frame goes in the way the terminal socket
   * puts it in — `sessions.write` is that handler's own call.
   */
  // spec-00006-AC-5.1
  it('commits a body-only save and hands its note to the next printable frame', async () => {
    const { spawn, written } = pens()
    const { call, board, repoRoot } = cowriteBoard({ 'integration/cli.md': DRAFT_INTEGRATION }, { spawn })
    const { body: started } = await call('POST', '/api/sessions/cowrite', { docId: 'integration-00001-cli' })
    const { body: opened } = await call('GET', '/api/docs/integration-00001-cli')

    const save = await call('PUT', '/api/docs/integration-00001-cli', {
      content: `${opened.content}the owner typed this\n`,
      baseHash: opened.hash,
    })
    board.sessions.write(started.sessionId, 'look again please')

    expect(save.status).toBe(200)
    expect(lastCommitMessage(repoRoot)).toBe('wb(edit): integration-00001-cli')
    expect(written.at(-2)).toBe('[用户已手改目标文档，动笔前须重读] ')
    expect(written.at(-1)).toBe('look again please')
  })

  // spec-00006-AC-5.2 — the hand edit landed in its own commit; the collapse adds none
  it('makes no collapse commit for a session whose only change was the owner’s own save', async () => {
    const { spawn, exit } = pens()
    const { call, board, repoRoot } = cowriteBoard({ 'integration/cli.md': DRAFT_INTEGRATION }, { spawn })
    const { body: started } = await call('POST', '/api/sessions/cowrite', { docId: 'integration-00001-cli' })
    const { body: opened } = await call('GET', '/api/docs/integration-00001-cli')
    await call('PUT', '/api/docs/integration-00001-cli', {
      content: `${opened.content}the owner typed this\n`,
      baseHash: opened.hash,
    })
    const commits = commitCount(repoRoot)

    exit()
    await board.sessions.whenFinished(started.sessionId)

    expect(commitCount(repoRoot)).toBe(commits)
    expect(board.sessions.latest()!.outcome).toMatchObject({ docId: 'integration-00001-cli', committed: false })
  })

  /**
   * spec-00006-AC-8.1 and AC-6.1 end to end, on a real agent process: the target
   * and the well-formed reference land in one commit named for the kind and the
   * document, and the rewrite of another document is put back
   * (rule-00001-AC-30.1, AC-30.2).
   */
  // spec-00006-AC-8.1
  // rule-00001-AC-28.1
  it('commits the target and its new reference in one commit, restoring what fell outside', async () => {
    const { call, board, repoRoot, docsDir } = cowriteBoard(
      { 'integration/cli.md': DRAFT_INTEGRATION, 'idea/a.md': DRAFT_IDEA },
      {
        args: [
          '-e',
          `const fs = require('fs');
           fs.appendFileSync('integration/cli.md', '\\nwritten together\\n');
           fs.mkdirSync('reference', { recursive: true });
           fs.writeFileSync('reference/reference-00001-cases.md', ${JSON.stringify(
             doc({ id: 'reference-00001-cases', type: 'reference', status: 'draft' }, '# Cases\n'),
           )});
           fs.appendFileSync('idea/a.md', '\\nout of scope\\n');`,
        ],
      },
    )

    const { body: started } = await call('POST', '/api/sessions/cowrite', { docId: 'integration-00001-cli' })
    await vi.waitFor(() => expect(board.sessions.latest()!.status).toBe('exited'), SESSION_WAIT)
    await board.sessions.whenFinished(started.sessionId)

    expect(lastCommitMessage(repoRoot)).toBe('wb(cowrite): integration-00001-cli')
    expect(lastCommitFiles(repoRoot).sort()).toEqual([
      'docs/integration/cli.md',
      'docs/reference/reference-00001-cases.md',
    ])
    expect(readFileSync(join(docsDir, 'integration/cli.md'), 'utf8')).toContain('status: draft')
    expect(readFileSync(join(docsDir, 'idea/a.md'), 'utf8')).toBe(DRAFT_IDEA)
  })

  // spec-00006-AC-8.3 — the commit says nothing about how the session ended
  it('commits the filtered changes the same way when the owner stops the session mid-write', async () => {
    const { call, board, repoRoot, docsDir } = cowriteBoard(
      { 'integration/cli.md': DRAFT_INTEGRATION },
      {
        args: [
          '-e',
          `require('fs').appendFileSync('integration/cli.md', '\\nhalf a sen');
           require('fs').mkdirSync('spec', { recursive: true });
           require('fs').writeFileSync('spec/invented.md', 'out of scope\\n');
           setTimeout(() => {}, 5000);`,
        ],
      },
    )
    const { body: started } = await call('POST', '/api/sessions/cowrite', { docId: 'integration-00001-cli' })
    await vi.waitFor(
      () => expect(readFileSync(join(docsDir, 'integration/cli.md'), 'utf8')).toContain('half a sen'),
      SESSION_WAIT,
    )

    await call('DELETE', `/api/sessions/${started.sessionId}`)
    await board.sessions.whenFinished(started.sessionId)

    expect(board.sessions.latest()!.status).toBe('terminated')
    expect(lastCommitMessage(repoRoot)).toBe('wb(cowrite): integration-00001-cli')
    expect(lastCommitFiles(repoRoot)).toEqual(['docs/integration/cli.md'])
    expect(existsSync(join(docsDir, 'spec/invented.md'))).toBe(false)
  })

  // spec-00006-AC-6.5 through the board's own wiring: the running session's
  // product is left for its own wrap-up
  it('leaves what another running session wrote to that session', async () => {
    const { spawn, exit } = pens()
    const { call, board, repoRoot, docsDir } = cowriteBoard(
      { 'integration/cli.md': DRAFT_INTEGRATION, 'spec/b.md': doc({ id: 'spec-00001-b', type: 'spec', status: 'draft' }) },
      { spawn },
    )
    const { body: started } = await call('POST', '/api/sessions/cowrite', { docId: 'integration-00001-cli' })
    await call('POST', '/api/sessions/audit', { docId: 'spec-00001-b' })
    appendFileSync(join(docsDir, 'spec/b.md'), '\nthe audit session wrote this\n')
    appendFileSync(join(docsDir, 'integration/cli.md'), '\nwritten together\n')

    exit(0)
    await board.sessions.whenFinished(started.sessionId)

    expect(lastCommitFiles(repoRoot)).toEqual(['docs/integration/cli.md'])
    expect(readFileSync(join(docsDir, 'spec/b.md'), 'utf8')).toContain('the audit session wrote this')
  })
})

/**
 * The annotation entries (spec-00007-FR-3, FR-5, design-00001 §12.3): the HTTP
 * contract — which code each refusal answers with and which word it carries — and
 * the two paths running end to end over the real receipt chains. What each path
 * decides is proved at the service level (annotations.test.ts).
 */
describe('doc annotations', () => {
  const PASSAGE = 'The gate is cheap to check.'
  const BODY = `# Spec\n\n${PASSAGE}\n\nAnd another sentence entirely.\n`
  const SPEC = (status: string) =>
    doc({ id: 'spec-00001-b', type: 'spec', status, parent: 'prd-00001-p' }, BODY)
  const RELATED_PRD = doc({ id: 'prd-00001-p', type: 'prd', status: 'active' }, '# Prd\n')
  const ARCHIVED = doc({ id: 'spec-00002-c', type: 'spec', status: 'archived' }, BODY)

  /** A pty stand-in that records what it was told, and a headless one that never ends. */
  function seams() {
    const written: string[] = []
    const exits: Array<(exitCode: number) => void> = []
    const spawn: SpawnPty = () => {
      const listeners: Array<(event: { exitCode: number }) => void> = []
      let gone = false
      const end = (exitCode: number) => {
        if (gone) return
        gone = true
        for (const listener of listeners) listener({ exitCode })
      }
      exits.push(end)
      return {
        onData: () => {},
        onExit: (listener) => void listeners.push(listener),
        write: (data) => void written.push(data),
        resize: () => {},
        kill: () => end(0),
      }
    }
    const payloads: string[] = []
    const spawnHeadless: SpawnHeadless = (_command, args) => {
      payloads.push(args.at(-1)!)
      return { onStdout: () => {}, onStderr: () => {}, onExit: () => {}, kill: () => {} }
    }
    return { spawn, spawnHeadless, written, payloads, exit: (index = 0) => exits[index]!(0) }
  }

  /**
   * `realCalls` runs the ask path on the config's own agent process instead of the
   * recording stand-in, which is what lets a thread actually finish;
   * `awaitThresholdMs` is the silence a session is read as waiting after.
   */
  function annotationBoard(
    status = 'draft',
    files: Record<string, string> = {},
    options: { realCalls?: boolean; awaitThresholdMs?: number; on?: { repoRoot: string; docsDir: string } } = {},
  ) {
    const { repoRoot, docsDir } = options.on ?? makeRepo({ 'spec/b.md': SPEC(status), 'prd/p.md': RELATED_PRD, ...files })
    const seam = seams()
    return {
      ...boardOnRepo(
        repoRoot,
        docsDir,
        testConfig(),
        seam.spawn,
        options.awaitThresholdMs,
        options.realCalls ? undefined : seam.spawnHeadless,
      ),
      ...seam,
    }
  }

  /** The anchor the editor would cut for that passage of the document on disk. */
  const anchorOf = (docsDir: string, relPath: string, selected: string) => {
    const text = readFileSync(join(docsDir, relPath), 'utf8')
    const at = text.indexOf(selected)
    return {
      selected,
      before: text.slice(Math.max(0, at - 40), at),
      after: text.slice(at + selected.length, at + selected.length + 40),
    }
  }

  const add = (call: BoardCall, docId: string, body: Record<string, unknown>) =>
    call('POST', `/api/annotations/${docId}`, body)

  // spec-00007-AC-9.9 — the list, the batches and the statement come in one read
  it('serves an empty list with the submit statement for a document nobody has annotated', async () => {
    const { call } = annotationBoard()

    const { status, body } = await call('GET', '/api/annotations/spec-00001-b')

    expect(status).toBe(200)
    expect(body).toEqual({
      annotations: [],
      batches: [],
      submitPreview: {
        questions: 0,
        issues: 0,
        willTransitionTo: null,
        issueEligible: true,
        questionEligible: true,
      },
    })
  })

  // spec-00007-AC-1.1 over the entry, with the reading of the anchor riding along
  it('records an annotation and serves it back with where its anchor lands', async () => {
    const { call, docsDir } = annotationBoard()

    const created = await add(call, 'spec-00001-b', {
      type: 'question',
      text: 'why two gates?',
      anchor: anchorOf(docsDir, 'spec/b.md', PASSAGE),
    })

    expect(created.status).toBe(201)
    expect(created.body.annotation).toMatchObject({ id: 'n-1', type: 'question', quote: PASSAGE, state: 'pending' })
    const { body } = await call('GET', '/api/annotations/spec-00001-b')
    expect(body.annotations[0].locate).toEqual({
      start: readFileSync(join(docsDir, 'spec/b.md'), 'utf8').indexOf(PASSAGE),
      end: readFileSync(join(docsDir, 'spec/b.md'), 'utf8').indexOf(PASSAGE) + PASSAGE.length,
    })
    expect(body.submitPreview).toMatchObject({ questions: 1, issues: 0 })
  })

  // spec-00007-AC-4.2 and AC-1.4 — the refusals of the add entry carry their word
  it('answers 422 with the reason for an ineligible type and for an empty text', async () => {
    const { call, docsDir } = annotationBoard('draft', { 'spec/c.md': ARCHIVED })
    const anchor = anchorOf(docsDir, 'spec/c.md', PASSAGE)

    const ineligible = await add(call, 'spec-00002-c', { type: 'issue', text: 'change this', anchor })
    const empty = await add(call, 'spec-00001-b', { type: 'question', text: '  ', anchor })

    expect([ineligible.status, ineligible.body.reason]).toEqual([422, 'type-ineligible'])
    expect([empty.status, empty.body.reason]).toEqual([422, 'empty-text'])
  })

  // The annotations of a document are addressable in their own right, so an id the
  // board cannot resolve is the document's refusal and not the store's
  it('answers 409 doc-missing when the document the add names is not there', async () => {
    const { call, docsDir } = annotationBoard()

    const { status, body } = await add(call, 'spec-00009-gone', {
      type: 'question',
      text: 'why?',
      anchor: anchorOf(docsDir, 'spec/b.md', PASSAGE),
    })

    expect([status, body.reason]).toEqual([409, 'doc-missing'])
  })

  // spec-00007-AC-3.1 and AC-3.4 over the entries: change, re-anchor, delete
  it('changes, re-anchors and drops an annotation', async () => {
    const { call, docsDir } = annotationBoard()
    const anchor = anchorOf(docsDir, 'spec/b.md', PASSAGE)
    await add(call, 'spec-00001-b', { type: 'question', text: 'why two gates?', anchor })
    await add(call, 'spec-00001-b', { type: 'issue', text: 'name the gate', anchor })

    const changed = await call('PATCH', '/api/annotations/spec-00001-b/n-1', {
      text: 'why two gates, really?',
      anchor: anchorOf(docsDir, 'spec/b.md', 'And another sentence entirely.'),
    })
    const dropped = await call('DELETE', '/api/annotations/spec-00001-b/n-2')

    expect(changed.status).toBe(200)
    expect(changed.body.annotation).toMatchObject({
      text: 'why two gates, really?',
      quote: 'And another sentence entirely.',
    })
    expect([dropped.status, dropped.body.annotationId]).toEqual([200, 'n-2'])
    const { body } = await call('GET', '/api/annotations/spec-00001-b')
    expect(body.annotations.map((one: { id: string }) => one.id)).toEqual(['n-1'])
  })

  it('answers 404 for an annotation neither entry can find', async () => {
    const { call } = annotationBoard()

    expect((await call('PATCH', '/api/annotations/spec-00001-b/n-9', { text: 'x' })).status).toBe(404)
    expect((await call('DELETE', '/api/annotations/spec-00001-b/n-9')).status).toBe(404)
  })

  // design-00001 §12.3 — a submitted annotation is out of both entries' reach
  it('answers 409 already-submitted for a change or a delete of one that has gone', async () => {
    const { call, docsDir } = annotationBoard()
    await add(call, 'spec-00001-b', {
      type: 'question',
      text: 'why two gates?',
      anchor: anchorOf(docsDir, 'spec/b.md', PASSAGE),
    })
    await call('POST', '/api/annotations/spec-00001-b/submit', {})

    const changed = await call('PATCH', '/api/annotations/spec-00001-b/n-1', { text: 'x' })
    const dropped = await call('DELETE', '/api/annotations/spec-00001-b/n-1')

    expect([changed.status, changed.body.reason]).toEqual([409, 'already-submitted'])
    expect([dropped.status, dropped.body.reason]).toEqual([409, 'already-submitted'])
  })

  // spec-00007-AC-5.3 and AC-5.4 — the whole-batch refusals, each with its word
  it('answers 422 for an empty submit and for an unsaved buffer', async () => {
    const { call, docsDir } = annotationBoard()

    const empty = await call('POST', '/api/annotations/spec-00001-b/submit', {})
    await add(call, 'spec-00001-b', {
      type: 'question',
      text: 'why?',
      anchor: anchorOf(docsDir, 'spec/b.md', PASSAGE),
    })
    const unsaved = await call('POST', '/api/annotations/spec-00001-b/submit', { unsavedChanges: true })

    expect([empty.status, empty.body.reason]).toEqual([422, 'empty-submit'])
    expect([unsaved.status, unsaved.body.reason]).toEqual([422, 'unsaved-buffer'])
  })

  /**
   * spec-00007-AC-10.6 — the document was deleted since the annotations were made:
   * the submit is refused whole and the annotations are kept. The id is resolved
   * against the tree read afresh, so no watch has to have fired first.
   */
  // spec-00007-AC-10.6
  it('answers 409 doc-missing when the document is gone, keeping the annotations', async () => {
    const { call, docsDir } = annotationBoard()
    await add(call, 'spec-00001-b', {
      type: 'issue',
      text: 'name the gate',
      anchor: anchorOf(docsDir, 'spec/b.md', PASSAGE),
    })
    rmSync(join(docsDir, 'spec/b.md'))

    const { status, body } = await call('POST', '/api/annotations/spec-00001-b/submit', {})

    expect([status, body.reason]).toEqual([409, 'doc-missing'])
    expect((await call('GET', '/api/annotations/spec-00001-b')).body.annotations).toHaveLength(1)
  })

  /**
   * spec-00007-AC-7.3 with AC-6.1 and AC-7.1 over the real chains: the transition
   * commit, one cowrite session carrying the issues, one ask thread per question
   * carrying its passage — and the thread is in the document's ask list, which is
   * the same list a typed question lands in.
   */
  // spec-00007-AC-5.1
  // spec-00007-AC-6.1
  // spec-00007-AC-7.1
  // spec-00007-AC-7.3
  it('submits both paths at once: the transition, the cowrite, and a thread per question', async () => {
    const { call, board, docsDir, repoRoot, written, payloads } = annotationBoard('active')
    const anchor = anchorOf(docsDir, 'spec/b.md', PASSAGE)
    await add(call, 'spec-00001-b', { type: 'question', text: 'why two gates?', anchor })
    await add(call, 'spec-00001-b', { type: 'issue', text: 'name the gate', anchor })
    const before = commitCount(repoRoot)

    const { status, body } = await call('POST', '/api/annotations/spec-00001-b/submit', {})

    expect(status).toBe(200)
    expect(body.transition).toEqual({ to: 'draft', committed: true })
    expect(body.submitted.questions).toEqual([
      { annotationId: 'n-1', threadId: 't-1', sessionId: expect.any(String) },
    ])
    expect(body.submitted.issues).toMatchObject({ batchId: 'b-1', annotationIds: ['n-2'] })
    expect(body.blocked).toEqual([])
    // The transition is the one commit this submit makes; the collapse's comes
    // when the session ends (spec-00007-FR-11).
    expect(commitCount(repoRoot)).toBe(before + 1)
    expect(lastCommitMessage(repoRoot)).toBe('wb(status): spec-00001-b')
    expect(readFileSync(join(docsDir, 'spec/b.md'), 'utf8')).toContain('status: draft')
    // The cowrite got the issues as its materials, and the session is in the registry.
    expect(written[0]).toContain('Issue 1 of 1 — the passage the owner marked in spec/b.md:')
    expect(written[0]).toContain(`[[${PASSAGE}]]`)
    expect(written[0]).toContain('What they want changed: name the gate')
    expect(board.sessions.list().map((session) => session.kind)).toEqual(['cowrite', 'ask'])
    // The question went through the ask receipt chain: its first call carries the
    // standing instruction, the marked passage and the question itself.
    expect(payloads[0]).toContain('spec/b.md')
    expect(payloads[0]).toContain('Modify no file')
    expect(payloads[0]).toContain(`The passage they marked, quoted from spec/b.md:`)
    expect(payloads[0]).toContain(`[[${PASSAGE}]]`)
    expect(payloads[0]!.endsWith('why two gates?')).toBe(true)
    const { body: asks } = await call('GET', '/api/asks/spec-00001-b')
    expect(asks.threads[0]).toMatchObject({ id: 't-1', agent: 'claude' })
    expect(asks.threads[0].exchanges[0]).toMatchObject({ question: expect.stringContaining('why two gates?') })
    // And the annotations hold their references, each of one kind.
    const { body: listed } = await call('GET', '/api/annotations/spec-00001-b')
    expect(listed.annotations[0]).toMatchObject({ state: 'submitted', threadId: 't-1' })
    expect(listed.annotations[1]).toMatchObject({ state: 'submitted', batchId: 'b-1' })
    expect(listed.batches[0]).toMatchObject({ status: 'cowriting', annotationIds: ['n-2'] })
  })

  /**
   * issue-00023 — the entries answer the state of the stored file rather than
   * pretending it is empty: a `GET` that served zero annotations over an
   * unreadable list is what would have the owner rebuild them over it.
   */
  it('answers 422 naming the file when the stored annotations cannot be read', async () => {
    const { call, docsDir, repoRoot } = annotationBoard()
    await add(call, 'spec-00001-b', {
      type: 'question',
      text: 'why two gates?',
      anchor: anchorOf(docsDir, 'spec/b.md', PASSAGE),
    })
    writeFileSync(join(repoRoot, '.whiteboard/annotations/spec-00001-b.json'), '{ not json')

    const read = await call('GET', '/api/annotations/spec-00001-b')
    const submitted = await call('POST', '/api/annotations/spec-00001-b/submit', {})

    for (const { status, body } of [read, submitted]) {
      expect(status).toBe(422)
      expect(body.error).toMatch(/spec-00001-b\.json cannot be read/)
      expect(body.reason).toBeUndefined()
    }
  })

  /**
   * issue-00023 — a document nobody annotated has no annotation file, and a
   * cowrite started by hand leaves none behind: every session's end goes through
   * the batch landing, and a landing with no batch to land changes nothing
   * (spec-00007-FR-8 — the session behaves no differently for the annotations).
   */
  it('leaves no annotation file behind for a cowrite nobody annotated', async () => {
    const { call, board, repoRoot, exit } = annotationBoard()

    const started = await call('POST', '/api/sessions/cowrite', { docId: 'spec-00001-b' })
    exit()
    await board.sessions.whenFinished(started.body.sessionId)

    expect(started.status).toBe(200)
    expect(existsSync(join(repoRoot, '.whiteboard/annotations'))).toBe(false)
  })

  /**
   * spec-00007-AC-10.8 — the batch was still being cowritten when the process
   * went: the next boot writes it off and hands its annotations back, so nothing on
   * disk says «being cowritten» when nothing is.
   */
  // spec-00007-AC-10.8
  it('writes off a batch the last process was killed with, at the next boot', async () => {
    const { call, docsDir, repoRoot } = annotationBoard()
    await add(call, 'spec-00001-b', {
      type: 'issue',
      text: 'name the gate',
      anchor: anchorOf(docsDir, 'spec/b.md', PASSAGE),
    })
    await call('POST', '/api/annotations/spec-00001-b/submit', {})

    // A crash leaves the file as it stands; the next boot is what reconciles it.
    const rebooted = boardOnRepo(repoRoot, docsDir, testConfig(), seams().spawn)

    const view = rebooted.board.annotations.list('spec-00001-b')
    expect(view.batches[0]).toMatchObject({ status: 'failed', commit: null })
    expect(view.annotations[0]).toMatchObject({ state: 'pending' })
    expect(view.annotations[0]!.batchId).toBeUndefined()
  })

  /** An issue on the document, submitted: the session that carries it, and the batch. */
  async function issueSubmitted(board: ReturnType<typeof annotationBoard>, text = 'name the gate') {
    await add(board.call, 'spec-00001-b', {
      type: 'issue',
      text,
      anchor: anchorOf(board.docsDir, 'spec/b.md', PASSAGE),
    })
    const { body } = await board.call('POST', '/api/annotations/spec-00001-b/submit', {})
    return body as SubmitResult
  }

  /**
   * spec-00007-AC-8.1 and AC-8.4 — the session an annotation submit started holds
   * the document exactly as a hand-started cowrite does: the advance is refused
   * for same-document exclusion, and the accept for the running-session state lock
   * (spec-00006-FR-10). Nothing about the refusals knows where the session came
   * from — which is the whole of FR-8.
   */
  // spec-00007-AC-8.1
  // spec-00007-AC-8.4
  it('refuses an advance and an accept on the document its own annotations are being cowritten on', async () => {
    const board = annotationBoard()
    const submitted = await issueSubmitted(board)

    const advance = await board.call('POST', '/api/sessions', { sourceId: 'spec-00001-b', targetType: 'design' })
    const accept = await board.call('POST', '/api/docs/spec-00001-b/review', { action: 'accept' })

    expect(submitted.submitted.issues).toMatchObject({ batchId: 'b-1' })
    expect([advance.status, advance.body.reason]).toEqual([409, 'doc-busy'])
    expect(advance.body.error).toMatch(/already has a running agent session/)
    expect([accept.status, accept.body.reason]).toEqual([409, 'doc-busy'])
  })

  /**
   * spec-00007-AC-8.2 — the collapse filter of the annotation-started session is
   * the cowrite's own (spec-00006-FR-6): the target's body lands, the rewrite of a
   * related document is put back, and the one commit names the target.
   */
  // spec-00007-AC-8.2
  it('restores what the annotation-started session wrote outside the target and commits the rest', async () => {
    const board = annotationBoard()
    const submitted = await issueSubmitted(board)

    appendFileSync(join(board.docsDir, 'spec/b.md'), '\nthe session named the gate.\n')
    appendFileSync(join(board.docsDir, 'prd/p.md'), '\nand wrote into the parent too\n')
    board.exit()
    await board.board.sessions.whenFinished(submitted.submitted.issues!.sessionId)

    expect(lastCommitMessage(board.repoRoot)).toBe('wb(cowrite): spec-00001-b')
    expect(lastCommitFiles(board.repoRoot)).toEqual(['docs/spec/b.md'])
    expect(readFileSync(join(board.docsDir, 'spec/b.md'), 'utf8')).toContain('the session named the gate.')
    expect(readFileSync(join(board.docsDir, 'prd/p.md'), 'utf8')).toBe(RELATED_PRD)
  })

  /**
   * spec-00007-AC-8.3 — silence past the threshold with the process alive reads as
   * awaiting input, the same as any other terminal session (spec-00003-FR-6). The
   * badge and the leave-behind notification are drawn off this one mark, and are
   * proved over it in the front end's own suite (web/test/sessions.test.tsx,
   * web/test/notifications.test.tsx) — which is why the mark is what is asserted here.
   */
  // spec-00007-AC-8.3
  it('reads the annotation-started session as awaiting input once it has gone quiet', async () => {
    const board = annotationBoard('draft', {}, { awaitThresholdMs: AWAIT_THRESHOLD })
    await issueSubmitted(board)

    await vi.waitFor(async () => {
      const { body } = await board.call('GET', '/api/sessions')
      expect(body.sessions[0]).toMatchObject({ kind: 'cowrite', sourceId: 'spec-00001-b', awaiting: true })
    }, SESSION_WAIT)
  })

  /**
   * spec-00007-AC-11.1 — the whole commit ledger of an issue submit that had to
   * transition first: the status commit at the submit, the collapse commit at the
   * end, and nothing else. spec-00007-AC-8.5 rides along on the same run — the
   * session neither promotes nor accepts, so the document is left on `draft` for
   * the owner to review.
   */
  // spec-00007-AC-8.5
  // spec-00007-AC-11.1
  it('makes exactly the transition and the collapse commit, leaving the document on draft', async () => {
    const board = annotationBoard('active')
    const before = commitCount(board.repoRoot)
    const submitted = await issueSubmitted(board)

    appendFileSync(join(board.docsDir, 'spec/b.md'), '\nthe session named the gate.\n')
    board.exit()
    await board.board.sessions.whenFinished(submitted.submitted.issues!.sessionId)

    expect(commitCount(board.repoRoot)).toBe(before + 2)
    expect(git(board.repoRoot, 'log', '-2', '--pretty=%s').trim().split('\n')).toEqual([
      'wb(cowrite): spec-00001-b',
      'wb(status): spec-00001-b',
    ])
    // The status the transition left is the status the session leaves behind.
    expect(readFileSync(join(board.docsDir, 'spec/b.md'), 'utf8')).toContain('status: draft')
    const { body: graph } = await board.call('GET', '/api/graph')
    expect(graph.nodes.find((one: { id: string }) => one.id === 'spec-00001-b')).toMatchObject({ status: 'draft' })
    // And the annotation store is no part of the ledger: neither commit staged it,
    // and nothing of it is tracked (the .gitignore half is annotationStore.test.ts).
    expect(git(board.repoRoot, 'ls-files')).not.toContain('.whiteboard')
    expect(git(board.repoRoot, 'show', '--name-only', '--pretty=', 'HEAD~1').trim()).toBe('docs/spec/b.md')
  })

  /**
   * spec-00007-AC-11.2 — the question path makes no commit anywhere: not at the
   * submit, not while the call runs, not when the thread has its answer. The call
   * is the config's own agent process here, so the thread really does finish.
   */
  // spec-00007-AC-11.2
  it('makes no commit at all for a submit of questions, thread and answer included', async () => {
    const board = annotationBoard('draft', {}, { realCalls: true })
    await add(board.call, 'spec-00001-b', {
      type: 'question',
      text: 'why two gates?',
      anchor: anchorOf(board.docsDir, 'spec/b.md', PASSAGE),
    })
    const before = commitCount(board.repoRoot)

    const { body } = await board.call('POST', '/api/annotations/spec-00001-b/submit', {})

    expect(body.submitted.questions).toHaveLength(1)
    expect(body.transition).toBeNull()
    await vi.waitFor(async () => {
      const { body: asks } = await board.call('GET', '/api/asks/spec-00001-b')
      expect(asks.threads[0].exchanges[0]).toMatchObject({ outcome: 'answered' })
    }, SESSION_WAIT)
    expect(commitCount(board.repoRoot)).toBe(before)
    expect(git(board.repoRoot, 'status', '--porcelain')).not.toContain('docs/')
  })

  /**
   * spec-00007-AC-3.1, AC-3.3 and AC-9.8 over a real restart: a second board on
   * the same repo is what a restart is, and everything the last one wrote comes
   * back — the unsubmitted annotation with its edit in place, the deleted one
   * still gone, the submitted question with its thread, and the finished batch
   * with its collapse commit. A batch still being cowritten is the one thing that
   * does move, and that move is AC-10.8's own test above.
   */
  // spec-00007-AC-3.1
  // spec-00007-AC-3.3
  // spec-00007-AC-9.8
  it('brings the annotations and their states back across a restart', async () => {
    const board = annotationBoard('draft', {}, { realCalls: true })
    const anchor = anchorOf(board.docsDir, 'spec/b.md', PASSAGE)
    const other = anchorOf(board.docsDir, 'spec/b.md', 'And another sentence entirely.')
    // One question and one issue to submit, and two more to keep unsubmitted —
    // of which one is edited and one dropped (AC-3.1).
    await add(board.call, 'spec-00001-b', { type: 'question', text: 'why two gates?', anchor })
    await add(board.call, 'spec-00001-b', { type: 'issue', text: 'name the gate', anchor })
    const submitted = (await board.call('POST', '/api/annotations/spec-00001-b/submit', {})).body as SubmitResult
    appendFileSync(join(board.docsDir, 'spec/b.md'), '\nthe session named the gate.\n')
    board.exit()
    await board.board.sessions.whenFinished(submitted.submitted.issues!.sessionId)
    await add(board.call, 'spec-00001-b', { type: 'question', text: 'and this one?', anchor: other })
    await add(board.call, 'spec-00001-b', { type: 'issue', text: 'to be dropped', anchor: other })
    await board.call('PATCH', '/api/annotations/spec-00001-b/n-3', { text: 'and this one, really?' })
    await board.call('DELETE', '/api/annotations/spec-00001-b/n-4')

    const restarted = annotationBoard('draft', {}, { on: { repoRoot: board.repoRoot, docsDir: board.docsDir } })
    const { body } = await restarted.call('GET', '/api/annotations/spec-00001-b')

    expect(body.annotations.map((one: { id: string; state: string }) => [one.id, one.state])).toEqual([
      ['n-1', 'submitted'],
      ['n-2', 'submitted'],
      ['n-3', 'pending'],
    ])
    expect(body.annotations[2]).toMatchObject({ text: 'and this one, really?', quote: 'And another sentence entirely.' })
    expect(body.annotations[0]).toMatchObject({ threadId: 't-1' })
    expect(body.batches).toEqual([
      expect.objectContaining({ id: 'b-1', status: 'done', annotationIds: ['n-2'], commit: expect.any(String) }),
    ])
    // The thread the question is mirrored from outlives the restart too.
    const { body: asks } = await restarted.call('GET', '/api/asks/spec-00001-b')
    expect(asks.threads[0]).toMatchObject({ id: 't-1' })
  })

  /**
   * spec-00007-AC-11.3 — the document was given another id, so nothing on the
   * board leads to the annotations of the old one any more: the graph has no such
   * node, and the editor that is the only way to the annotation list is therefore
   * unreachable. The file itself is kept (the reclaim is a later round).
   */
  // spec-00007-AC-11.3
  it('leaves the annotations of a renamed document unreachable and on disk', async () => {
    const { call, board, docsDir, repoRoot } = annotationBoard()
    await add(call, 'spec-00001-b', {
      type: 'question',
      text: 'why two gates?',
      anchor: anchorOf(docsDir, 'spec/b.md', PASSAGE),
    })

    writeFileSync(join(docsDir, 'spec/b.md'), SPEC('draft').replace('spec-00001-b', 'spec-00003-d'))
    // What the watch does when it sees the write (spec-00001-FR-42); the watch
    // itself is proved in its own tests, and armed here it would only add a wait.
    board.docs.invalidate()

    const { body: graph } = await call('GET', '/api/graph')
    expect(graph.nodes.map((one: { id: string }) => one.id)).not.toContain('spec-00001-b')
    // Nothing of the old id is offered for annotating either, so no entry can lead
    // back to it (spec-00007-FR-4's anomalous/absent branch).
    const { body } = await call('GET', '/api/annotations/spec-00001-b')
    expect(body.submitPreview).toMatchObject({ issueEligible: false, questionEligible: false })
    expect(existsSync(join(repoRoot, '.whiteboard/annotations/spec-00001-b.json'))).toBe(true)
  })

  /**
   * spec-00007-AC-11.4 — the same for a document deleted outright, with a batch
   * that has already finished on it: the node is gone, and the record of what was
   * done is kept exactly as it stood.
   */
  // spec-00007-AC-11.4
  it('leaves the annotations of a deleted document unreachable and on disk, batch and all', async () => {
    const board = annotationBoard()
    const submitted = await issueSubmitted(board)
    appendFileSync(join(board.docsDir, 'spec/b.md'), '\nthe session named the gate.\n')
    board.exit()
    await board.board.sessions.whenFinished(submitted.submitted.issues!.sessionId)

    rmSync(join(board.docsDir, 'spec/b.md'))
    board.board.docs.invalidate()

    const { body: graph } = await board.call('GET', '/api/graph')
    expect(graph.nodes.map((one: { id: string }) => one.id)).not.toContain('spec-00001-b')
    const { body } = await board.call('GET', '/api/annotations/spec-00001-b')
    expect(body.batches[0]).toMatchObject({ status: 'done', commit: expect.any(String) })
    expect(body.submitPreview).toMatchObject({ issueEligible: false, questionEligible: false })
    expect(existsSync(join(board.repoRoot, '.whiteboard/annotations/spec-00001-b.json'))).toBe(true)
  })
})

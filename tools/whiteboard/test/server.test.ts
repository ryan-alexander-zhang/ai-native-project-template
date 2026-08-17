import type { Server } from 'node:http'
import { readFileSync, rmSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { Board } from '../src/server.ts'
import { spawnPty } from '../src/pty.ts'
import { SESSION_WAIT, commitCount, doc, lastCommitMessage, makeRepo, testConfig } from './helpers.ts'

const DRAFT_IDEA = doc({ id: 'idea-00001-x', type: 'idea', status: 'draft' }, '# Idea X\n')
const ACTIVE_IDEA = doc({ id: 'idea-00001-x', type: 'idea', status: 'active' }, '# Idea X\n')
const servers: Server[] = []

/** Start a board on an ephemeral port and give back a fetch bound to it. */
function boardOn(files: Record<string, string>, agentArgs = ['-e', '']) {
  const { repoRoot, docsDir } = makeRepo(files)
  const config = testConfig()
  config.agents[0] = { ...config.agents[0]!, args: agentArgs }
  const board = new Board({ repoRoot, docsDir, config, spawn: spawnPty })
  const server = board.listen(0)
  servers.push(server)
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

afterEach(() => {
  for (const server of servers.splice(0)) server.close()
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

  // spec-00001-AC-9.1
  it('records clarify questions and keeps the draft', async () => {
    const { call, docsDir } = boardOn({ 'idea/a.md': DRAFT_IDEA })

    const { body } = await call('POST', '/api/docs/idea-00001-x/review', {
      action: 'clarify',
      questions: ['who owns this?'],
    })

    expect(body.status).toBe('draft')
    expect(readFileSync(join(docsDir, 'idea/a.md'), 'utf8')).toContain('- who owns this?')
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

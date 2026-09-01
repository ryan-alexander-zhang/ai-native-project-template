import type { Server } from 'node:http'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { spawnPty } from '../src/pty.ts'
import { Board } from '../src/server.ts'
import { SESSION_WAIT, doc, git, makeRepo, relationEdge, testConfig } from './helpers.ts'

/**
 * The acceptance path of plan-00001: the five stories walked end to end over the
 * HTTP surface, on a real git repo, asserting the files and the commit trail.
 */
const servers: Server[] = []

const IDEA = doc(
  { id: 'idea-00001-whiteboard', type: 'idea', status: 'draft' },
  '# Docs Whiteboard\n\nA board over the docs tree.\n',
)

/** An agent that writes the prd it was asked for, then exits. */
const AGENT_WRITES_PRD = [
  '-e',
  `const fs = require('fs');
   fs.mkdirSync('prd', { recursive: true });
   fs.writeFileSync('prd/whiteboard.md', ${JSON.stringify(
     doc(
       { id: 'prd-00001-whiteboard', type: 'prd', status: 'draft', parent: 'idea-00001-whiteboard' },
       '# Docs Whiteboard PRD\n',
     ),
   )});`,
]

function startBoard(agentArgs: string[], files: Record<string, string> = { 'idea/whiteboard.md': IDEA }) {
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
  return { board, repoRoot, docsDir, call }
}

function commitTrail(repoRoot: string): string[] {
  return git(repoRoot, 'log', '--pretty=%s', '--reverse').trim().split('\n')
}

afterEach(() => {
  for (const server of servers.splice(0)) server.close()
})

describe('the whiteboard acceptance path', () => {
  /**
   * S1 -> S2 -> S3: see the board, edit a document, review it. Clarify is a
   * session as of the eighth round (decision-00006), so the questions arrive in
   * the document from inside a session — walked here as the clarify session's
   * agent writing them, which is the state the accept gate then reads.
   */
  it('walks a draft idea from the board through clarify to accept', async () => {
    const { call, board, repoRoot, docsDir } = startBoard([
      '-e',
      `require('fs').appendFileSync('idea/whiteboard.md', '\\n## Open Questions\\n\\n- who owns the flow config?\\n')`,
    ])

    // S1: the board shows the docs tree with no issues
    const graph = await call('GET', '/api/graph')
    expect(graph.body.nodes).toHaveLength(1)
    expect(graph.body.nodes[0].title).toBe('Docs Whiteboard')
    expect(graph.body.issues).toEqual([])

    // S2: edit the document in place
    const opened = await call('GET', '/api/docs/idea-00001-whiteboard')
    const edited = opened.body.content.replace('A board over the docs tree.', 'A board over the docs tree, MVP.')
    expect((await call('PUT', '/api/docs/idea-00001-whiteboard', { content: edited, baseHash: opened.body.hash })).body)
      .toMatchObject({ committed: true })

    // S3: clarify first — a session, and its agent leaves the question behind;
    // the document stays draft and accept is refused while the question stands
    const session = await call('POST', '/api/sessions/clarify', { docId: 'idea-00001-whiteboard' })
    expect(session.body.kind).toBe('clarify')
    await vi.waitFor(() => expect(board.sessions.latest()!.status).toBe('exited'), SESSION_WAIT)
    await board.sessions.whenFinished()

    const clarified = await call('GET', '/api/docs/idea-00001-whiteboard')
    expect(clarified.body.content).toContain('- who owns the flow config?')
    expect(clarified.body.content).toContain('status: draft')

    const refused = await call('POST', '/api/docs/idea-00001-whiteboard/review', { action: 'accept' })
    expect(refused.status).toBe(422)
    expect(refused.body.error).toMatch(/unresolved open questions/)

    // the question is answered, the section goes, and accept promotes the idea
    const withQuestions = await call('GET', '/api/docs/idea-00001-whiteboard')
    const resolved = withQuestions.body.content.replace(/\n## Open Questions[\s\S]*$/, '\n')
    await call('PUT', '/api/docs/idea-00001-whiteboard', { content: resolved, baseHash: withQuestions.body.hash })
    expect((await call('POST', '/api/docs/idea-00001-whiteboard/review', { action: 'accept' })).body.status).toBe(
      'active',
    )

    expect(readFileSync(join(docsDir, 'idea/whiteboard.md'), 'utf8')).toContain('status: active')
    // S5: every step left its own commit, in order
    expect(commitTrail(repoRoot)).toEqual([
      'init',
      'wb(edit): idea-00001-whiteboard',
      'wb(clarify): idea-00001-whiteboard',
      'wb(edit): idea-00001-whiteboard',
      'wb(accept): idea-00001-whiteboard',
    ])
  })

  // S4: advance to the next stage and let the agent write it
  it('advances an idea into a prd the agent writes, and commits it', async () => {
    const { call, board, repoRoot, docsDir } = startBoard(AGENT_WRITES_PRD)
    await call('POST', '/api/docs/idea-00001-whiteboard/review', { action: 'accept' })

    // the flow config offers prd and spec; the board starts the prd session
    expect((await call('GET', '/api/docs/idea-00001-whiteboard/next-steps')).body).toEqual([
      { next: 'prd', carry: 'parent' },
      { next: 'spec', carry: 'parent' },
    ])
    const session = await call('POST', '/api/sessions', {
      sourceId: 'idea-00001-whiteboard',
      targetType: 'prd',
    })
    expect(session.body.status).toBe('running')

    await vi.waitFor(() => expect(board.sessions.latest()!.status).toBe('exited'), SESSION_WAIT)
    await board.sessions.whenFinished()

    // the product is on disk, sound, committed, and on the board with its edge
    expect(readFileSync(join(docsDir, 'prd/whiteboard.md'), 'utf8')).toContain('parent: idea-00001-whiteboard')
    expect(board.sessions.latest()!.outcome).toMatchObject({ docId: 'prd-00001-whiteboard', problems: [] })
    expect(commitTrail(repoRoot)).toEqual([
      'init',
      'wb(accept): idea-00001-whiteboard',
      'wb(advance): prd-00001-whiteboard',
    ])

    const graph = await call('GET', '/api/graph')
    expect(graph.body.nodes.map((node: { id: string }) => node.id).sort()).toEqual([
      'idea-00001-whiteboard',
      'prd-00001-whiteboard',
    ])
    expect(graph.body.edges).toEqual([
      relationEdge('prd-00001-whiteboard', 'idea-00001-whiteboard', 'parent'),
    ])
    expect(graph.body.issues).toEqual([])
  })

  // rule-00001-AC-15.2 and AC-15.3: a spec advances into docs that point back at it
  it('advances a spec into a rule carrying informs and a plan carrying implements', async () => {
    const spec = doc({ id: 'spec-00001-board', type: 'spec', status: 'active' }, '# Spec\n')
    const product = (front: Record<string, string>, file: string) =>
      `const fs = require('fs');
       fs.mkdirSync('${front.type}', { recursive: true });
       fs.writeFileSync('${front.type}/${file}', ${JSON.stringify(doc(front, '# Product\n'))});`

    const { repoRoot, docsDir } = makeRepo({ 'idea/whiteboard.md': IDEA, 'spec/board.md': spec })
    const config = testConfig()

    for (const [targetType, front] of [
      ['rule', { id: 'rule-00001-flow', type: 'rule', status: 'draft', informs: '[spec-00001-board]' }],
      ['plan', { id: 'plan-00001-mvp', type: 'plan', status: 'draft', implements: '[spec-00001-board]' }],
    ] as const) {
      config.agents[0] = { ...config.agents[0]!, args: ['-e', product(front, 'x.md')] }
      const board = new Board({ repoRoot, docsDir, config, spawn: spawnPty })
      const server = board.listen(0)
      servers.push(server)
      const port = (server.address() as { port: number }).port

      await fetch(`http://127.0.0.1:${port}/api/sessions`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ sourceId: 'spec-00001-board', targetType }),
      })
      await vi.waitFor(() => expect(board.sessions.latest()!.status).toBe('exited'), SESSION_WAIT)
      await board.sessions.whenFinished()

      // the product carries the relation the flow config told the agent to carry
      expect(board.sessions.latest()!.outcome).toMatchObject({ docId: front.id, problems: [] })
    }

    const graph = new Board({ repoRoot, docsDir, config, spawn: spawnPty }).graph()
    expect(graph.edges).toEqual([
      relationEdge('plan-00001-mvp', 'spec-00001-board', 'implements'),
      relationEdge('rule-00001-flow', 'spec-00001-board', 'informs'),
    ])
  })

  // rule-00001-AC-16.2 and AC-16.3: a plan advances into the two documents the
  // implementation phase produces, each carrying the relation the flow gave it
  it('advances a plan into a record carrying parent and an issue carrying blocks', async () => {
    const plan = doc({ id: 'plan-00001-mvp', type: 'plan', status: 'open' }, '# Plan\n')

    for (const [targetType, front] of [
      ['record', { id: 'record-00001-run', type: 'record', status: 'draft', parent: 'plan-00001-mvp' }],
      ['issue', { id: 'issue-00001-bug', type: 'issue', status: 'draft', blocks: '[plan-00001-mvp]' }],
    ] as const) {
      const { call, board } = startBoard(
        [
          '-e',
          `const fs = require('fs');
           fs.mkdirSync('${front.type}', { recursive: true });
           fs.writeFileSync('${front.type}/x.md', ${JSON.stringify(doc(front, '# Product\n'))});`,
        ],
        { 'plan/mvp.md': plan },
      )

      expect((await call('GET', '/api/docs/plan-00001-mvp/next-steps')).body).toEqual([
        { next: 'task', carry: 'parent' },
        { next: 'issue', carry: 'blocks' },
        { next: 'record', carry: 'parent' },
      ])
      await call('POST', '/api/sessions', { sourceId: 'plan-00001-mvp', targetType })
      await vi.waitFor(() => expect(board.sessions.latest()!.status).toBe('exited'), SESSION_WAIT)
      await board.sessions.whenFinished()

      // no problem reported is the relation being the one it was told to carry
      expect(board.sessions.latest()!.outcome).toMatchObject({ docId: front.id, problems: [] })
    }
  })

  // spec-00001-AC-14.4: everything one session wrote lands in a single commit
  it('commits every file a session touched under one advance commit', async () => {
    const { call, board, repoRoot } = startBoard([
      '-e',
      `const fs = require('fs');
       fs.mkdirSync('prd', { recursive: true });
       fs.writeFileSync('prd/whiteboard.md', ${JSON.stringify(
         doc({ id: 'prd-00001-whiteboard', type: 'prd', status: 'draft', parent: 'idea-00001-whiteboard' }, '# P\n'),
       )});
       fs.writeFileSync('prd/notes.md', 'scratch the agent also wrote');`,
    ])
    await call('POST', '/api/docs/idea-00001-whiteboard/review', { action: 'accept' })

    await call('POST', '/api/sessions', { sourceId: 'idea-00001-whiteboard', targetType: 'prd' })
    await vi.waitFor(() => expect(board.sessions.latest()!.status).toBe('exited'), SESSION_WAIT)
    await board.sessions.whenFinished()

    expect(git(repoRoot, 'show', '--name-only', '--pretty=', 'HEAD').trim().split('\n').sort()).toEqual([
      'docs/prd/notes.md',
      'docs/prd/whiteboard.md',
    ])
    expect(commitTrail(repoRoot)).toHaveLength(3)
  })

  /**
   * S10: audit a draft, and let the accept gate do the rest. The findings land in
   * the audited document itself, so the unresolved one is what BR-12 stops the
   * promotion on — no second gate is needed (decision-00007 §2 第 3 条).
   */
  it('audits a draft spec, lands the finding in it, and has accept refuse the promotion', async () => {
    const spec = doc({ id: 'spec-00001-board', type: 'spec', status: 'draft' }, '# Spec\n\nOne requirement.\n')
    const { call, board, repoRoot, docsDir } = startBoard(
      ['-e', `require('fs').appendFileSync('spec/board.md', '\\n## Open Questions\\n\\n- FR-1 has no AC at all\\n')`],
      { 'spec/board.md': spec },
    )

    const session = await call('POST', '/api/sessions/audit', { docId: 'spec-00001-board' })
    expect(session.body.kind).toBe('audit')
    await vi.waitFor(() => expect(board.sessions.latest()!.status).toBe('exited'), SESSION_WAIT)
    await board.sessions.whenFinished()

    // the finding is in the document, and the audit left its status where it was
    const audited = await call('GET', '/api/docs/spec-00001-board')
    expect(audited.body.content).toContain('- FR-1 has no AC at all')
    expect(audited.body.content).toContain('status: draft')

    const refused = await call('POST', '/api/docs/spec-00001-board/review', { action: 'accept' })
    expect(refused.status).toBe(422)
    expect(refused.body.error).toMatch(/unresolved open questions/)

    expect(readFileSync(join(docsDir, 'spec/board.md'), 'utf8')).toContain('status: draft')
    expect(commitTrail(repoRoot)).toEqual(['init', 'wb(audit): spec-00001-board'])
  })

  /**
   * S11: the resolved gate as a user meets it — refused with the gap named, then
   * granted once the record that names the plan covers the scope (FR-52).
   */
  it('refuses a plan the records do not cover, and lets it resolve once they do', async () => {
    const spec = doc(
      { id: 'spec-00001-board', type: 'spec', status: 'active' },
      [
        '# Spec',
        '',
        '- **spec-00001-FR-1** (Event) the board shall gate the promotion',
        '',
        '- **spec-00001-AC-1.1** (spec-00001-FR-1)',
        '  Given a plan whose scope is unverified',
        '  When it is promoted',
        '  Then the gate refuses',
        '',
      ].join('\n'),
    )
    const record = doc(
      { id: 'record-00001-gate', type: 'record', status: 'active', parent: 'plan-00001-mvp' },
      ['# 验收记录', '', '| GWT id | 测试 | 结果 |', '| --- | --- | --- |', ''].join('\n'),
    )
    const { call } = startBoard(['-e', ''], {
      'spec/board.md': spec,
      'plan/mvp.md': doc({ id: 'plan-00001-mvp', type: 'plan', status: 'open', implements: '[spec-00001-FR-1]' }),
      'record/gate.md': record,
    })

    const refused = await call('POST', '/api/docs/plan-00001-mvp/status', { to: 'resolved' })
    expect(refused.status).toBe(422)
    expect(refused.body.gaps).toEqual(['spec-00001-FR-1'])

    // the acceptance row arrives, and the same request goes through
    const opened = await call('GET', '/api/docs/record-00001-gate')
    await call('PUT', '/api/docs/record-00001-gate', {
      content: `${opened.body.content}| spec-00001-AC-1.1 | server.test.ts | pass |\n`,
      baseHash: opened.body.hash,
    })

    const granted = await call('POST', '/api/docs/plan-00001-mvp/status', { to: 'resolved' })
    expect(granted.status).toBe(200)
    expect(granted.body).toMatchObject({ committed: true, status: 'resolved' })
  })

  // the agent that ignores the brief leaves a marked node, not a silent one
  it('marks a product that ignores the relation it was told to carry', async () => {
    const stray = doc({ id: 'prd-00001-stray', type: 'prd', status: 'draft' }, '# Stray\n')
    const { call, board } = startBoard([
      '-e',
      `const fs = require('fs');
       fs.mkdirSync('prd', { recursive: true });
       fs.writeFileSync('prd/stray.md', ${JSON.stringify(stray)});`,
    ])
    await call('POST', '/api/docs/idea-00001-whiteboard/review', { action: 'accept' })

    await call('POST', '/api/sessions', { sourceId: 'idea-00001-whiteboard', targetType: 'prd' })
    await vi.waitFor(() => expect(board.sessions.latest()!.status).toBe('exited'), SESSION_WAIT)
    await board.sessions.whenFinished()

    const node = (await call('GET', '/api/graph')).body.nodes.find(
      (candidate: { id: string }) => candidate.id === 'prd-00001-stray',
    )
    expect(node.ok).toBe(false)
    expect(node.problems).toContain('parent does not point at idea-00001-whiteboard')
  })
})

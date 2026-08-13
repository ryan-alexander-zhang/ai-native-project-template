import type { Server } from 'node:http'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { spawnPty } from '../src/pty.ts'
import { Board } from '../src/server.ts'
import { doc, git, makeRepo, testConfig } from './helpers.ts'

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

function startBoard(agentArgs: string[]) {
  const { repoRoot, docsDir } = makeRepo({ 'idea/whiteboard.md': IDEA })
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
  // S1 -> S2 -> S3: see the board, edit a document, review it
  it('walks a draft idea from the board through clarify to accept', async () => {
    const { call, repoRoot, docsDir } = startBoard(['-e', ''])

    // S1: the board shows the docs tree with no issues
    const graph = await call('GET', '/api/graph')
    expect(graph.body.nodes).toHaveLength(1)
    expect(graph.body.nodes[0].title).toBe('Docs Whiteboard')
    expect(graph.body.issues).toEqual([])

    // S2: edit the document in place
    const opened = await call('GET', '/api/docs/idea-00001-whiteboard')
    const edited = opened.body.content.replace('A board over the docs tree.', 'A board over the docs tree, MVP.')
    expect((await call('PUT', '/api/docs/idea-00001-whiteboard', { content: edited, baseHash: opened.body.hash })).body)
      .toEqual({ committed: true })

    // S3: clarify first — the document stays draft and accept is refused while questions stand
    await call('POST', '/api/docs/idea-00001-whiteboard/review', {
      action: 'clarify',
      questions: ['who owns the flow config?'],
    })
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

    await vi.waitFor(() => expect(board.sessions.current()!.status).toBe('exited'))
    await board.sessions.whenFinished()

    // the product is on disk, sound, committed, and on the board with its edge
    expect(readFileSync(join(docsDir, 'prd/whiteboard.md'), 'utf8')).toContain('parent: idea-00001-whiteboard')
    expect(board.sessions.current()!.outcome).toMatchObject({ docId: 'prd-00001-whiteboard', problems: [] })
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
      { from: 'prd-00001-whiteboard', to: 'idea-00001-whiteboard', relation: 'parent', ok: true },
    ])
    expect(graph.body.issues).toEqual([])
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
    await vi.waitFor(() => expect(board.sessions.current()!.status).toBe('exited'))
    await board.sessions.whenFinished()

    const node = (await call('GET', '/api/graph')).body.nodes.find(
      (candidate: { id: string }) => candidate.id === 'prd-00001-stray',
    )
    expect(node.ok).toBe(false)
    expect(node.problems).toContain('parent does not point at idea-00001-whiteboard')
  })
})

import { describe, expect, it } from 'vitest'
import { contentHash, findNode, highestNumber, readDocContent, readGraph } from '../src/docRepository.ts'
import { doc, makeDocsDir, testConfig } from './helpers.ts'

const config = testConfig()

const IDEA = doc({ id: 'idea-00001-whiteboard', type: 'idea', status: 'active' }, '# Docs Whiteboard\n\nbody\n')
const PRD = doc(
  { id: 'prd-00001-whiteboard', type: 'prd', status: 'active', parent: 'idea-00001-whiteboard' },
  '# Docs Whiteboard PRD\n',
)
const SPEC = doc(
  { id: 'spec-00001-whiteboard', type: 'spec', status: 'active', parent: 'prd-00001-whiteboard' },
  '# Spec: Whiteboard\n',
)

function graphOf(files: Record<string, string>) {
  return readGraph(makeDocsDir(files), config)
}

describe('readGraph', () => {
  // spec-00001-AC-1.1
  it('makes one node per document and one edge per relation field', () => {
    const graph = graphOf({ 'idea/a.md': IDEA, 'prd/b.md': PRD, 'spec/c.md': SPEC })

    expect(graph.nodes.map((node) => node.id)).toEqual([
      'idea-00001-whiteboard',
      'prd-00001-whiteboard',
      'spec-00001-whiteboard',
    ])
    expect(graph.edges).toEqual([
      { from: 'prd-00001-whiteboard', to: 'idea-00001-whiteboard', relation: 'parent', ok: true },
      { from: 'spec-00001-whiteboard', to: 'prd-00001-whiteboard', relation: 'parent', ok: true },
    ])
    expect(graph.issues).toEqual([])
  })

  it('makes one edge per id in a multi-valued relation', () => {
    const plan = doc({
      id: 'plan-00001-mvp',
      type: 'plan',
      status: 'open',
      implements: '[spec-00001-whiteboard, design-00001-whiteboard]',
    })
    const design = doc({ id: 'design-00001-whiteboard', type: 'design', status: 'active' })
    const graph = graphOf({ 'spec/c.md': SPEC, 'design/d.md': design, 'plan/p.md': plan })

    expect(graph.edges.filter((edge) => edge.relation === 'implements')).toEqual([
      { from: 'plan-00001-mvp', to: 'spec-00001-whiteboard', relation: 'implements', ok: true },
      { from: 'plan-00001-mvp', to: 'design-00001-whiteboard', relation: 'implements', ok: true },
    ])
  })

  // spec-00001-AC-1.3
  it('leaves README and TEMPLATE files out of the graph', () => {
    const graph = graphOf({
      'idea/a.md': IDEA,
      'idea/README.md': '# Ideas\n',
      'idea/TEMPLATE.md': doc({ id: 'idea-00001-example-slug', type: 'idea', status: 'draft' }),
    })
    expect(graph.nodes).toHaveLength(1)
    expect(graph.nodes[0]!.path).toBe('idea/a.md')
  })

  // spec-00001-AC-1.4
  it('yields an empty graph for an empty docs tree', () => {
    expect(graphOf({})).toEqual({ nodes: [], edges: [], issues: [] })
  })

  it('yields an empty graph when the docs directory does not exist', () => {
    expect(readGraph('/nonexistent/docs', config)).toEqual({ nodes: [], edges: [], issues: [] })
  })

  // spec-00001-AC-1.5
  it('takes the node title from the first H1', () => {
    const graph = graphOf({ 'prd/b.md': PRD })
    expect(graph.nodes[0]!.title).toBe('Docs Whiteboard PRD')
  })

  it('falls back to the file name when the document has no H1', () => {
    const graph = graphOf({ 'prd/no-heading.md': doc({ id: 'prd-00002-x', type: 'prd', status: 'draft' }, 'body\n') })
    expect(graph.nodes[0]!.title).toBe('no-heading')
  })

  // spec-00001-AC-2.1
  it('marks a document without front matter and labels it by path, leaving the rest intact', () => {
    const graph = graphOf({ 'idea/a.md': IDEA, 'prd/broken.md': '# No front matter\n\nbody\n' })

    const broken = graph.nodes.find((node) => node.path === 'prd/broken.md')!
    expect(broken.ok).toBe(false)
    expect(broken.id).toBe('prd/broken.md')
    expect(broken.problems).toEqual(['front matter is missing'])
    expect(graph.nodes.find((node) => node.id === 'idea-00001-whiteboard')!.ok).toBe(true)
    expect(graph.issues).toEqual([{ path: 'prd/broken.md', message: 'front matter is missing' }])
  })

  it('marks a document whose front matter is not valid YAML', () => {
    const graph = graphOf({ 'prd/bad.md': '---\nid: [unclosed\n---\n\nbody\n' })
    expect(graph.nodes[0]!.ok).toBe(false)
    expect(graph.nodes[0]!.problems[0]).toMatch(/not valid YAML/)
  })

  // spec-00001-AC-2.2
  it('marks an edge pointing at an unknown id and keeps the graph usable', () => {
    const graph = graphOf({ 'idea/a.md': IDEA, 'prd/b.md': doc({ ...frontMatterOf(PRD), parent: 'idea-09999-ghost' }) })

    expect(graph.edges).toEqual([
      { from: 'prd-00001-whiteboard', to: 'idea-09999-ghost', relation: 'parent', ok: false },
    ])
    expect(graph.issues).toEqual([
      { path: 'prd/b.md', message: 'parent points at unknown document "idea-09999-ghost"' },
    ])
    expect(graph.nodes.every((node) => node.ok)).toBe(true)
  })

  it('treats an edge into an anomalous document as unknown', () => {
    const graph = graphOf({
      'idea/a.md': doc({ id: 'idea-1-bad-number', type: 'idea', status: 'active' }),
      'prd/b.md': PRD,
    })
    expect(graph.edges[0]!.ok).toBe(false)
  })

  // spec-00001-AC-2.3
  it('marks a document whose id does not match the id format', () => {
    const graph = graphOf({ 'idea/a.md': doc({ id: 'idea-1-whiteboard', type: 'idea', status: 'active' }) })
    expect(graph.nodes[0]!.ok).toBe(false)
    expect(graph.nodes[0]!.problems).toEqual(['id "idea-1-whiteboard" does not match <type>-<nnnnn>-<slug>'])
  })

  it('marks a document whose id does not start with its own type', () => {
    const graph = graphOf({ 'idea/a.md': doc({ id: 'prd-00001-whiteboard', type: 'idea', status: 'active' }) })
    expect(graph.nodes[0]!.problems).toEqual(['id "prd-00001-whiteboard" does not start with its type "idea"'])
  })

  it('marks a document with no id at all', () => {
    const graph = graphOf({ 'idea/a.md': doc({ type: 'idea', status: 'active' }) })
    expect(graph.nodes[0]!.problems).toEqual(['front matter has no id'])
  })

  it('marks a document whose type is not in the flow config', () => {
    const graph = graphOf({ 'idea/a.md': doc({ id: 'memo-00001-x', type: 'memo', status: 'active' }) })
    expect(graph.nodes[0]!.problems).toContain('type "memo" is not a type in the flow config')
  })

  it('marks a document whose status is not a status of its kind', () => {
    const graph = graphOf({ 'idea/a.md': doc({ id: 'idea-00001-x', type: 'idea', status: 'resolved' }) })
    expect(graph.nodes[0]!.problems).toEqual(['status "resolved" is not a status of a living document'])
  })

  it('marks a document with no status', () => {
    const graph = graphOf({ 'idea/a.md': doc({ id: 'idea-00001-x', type: 'idea' }) })
    expect(graph.nodes[0]!.problems).toEqual(['status undefined is not a status of a living document'])
  })

  it('ignores relation values that are neither string nor list of strings', () => {
    const graph = graphOf({ 'prd/b.md': doc({ ...frontMatterOf(PRD), parent: '{ a: 1 }' }) })
    expect(graph.edges).toEqual([])
  })
})

describe('readDocContent', () => {
  it('reads the whole file, front matter included, with its hash', () => {
    const docsDir = makeDocsDir({ 'idea/a.md': IDEA })
    const graph = readGraph(docsDir, config)

    const content = readDocContent(docsDir, graph.nodes[0]!)
    expect(content.content).toBe(IDEA)
    expect(content.content).toContain('id: idea-00001-whiteboard')
    expect(content.hash).toBe(contentHash(IDEA))
  })
})

describe('findNode', () => {
  it('finds a node by id and returns undefined for an unknown one', () => {
    const graph = graphOf({ 'idea/a.md': IDEA })
    expect(findNode(graph, 'idea-00001-whiteboard')!.path).toBe('idea/a.md')
    expect(findNode(graph, 'idea-09999-ghost')).toBeUndefined()
  })
})

// rule-00001-BR-18
describe('highestNumber', () => {
  it('returns the highest number in use for a type', () => {
    const graph = graphOf({
      'prd/a.md': PRD,
      'prd/b.md': doc({ id: 'prd-00007-later', type: 'prd', status: 'active' }),
      'spec/c.md': SPEC,
    })
    expect(highestNumber(graph, 'prd')).toBe(7)
  })

  // rule-00001-AC-18.2 — no documents of that type yet
  it('returns 0 for a type with no documents', () => {
    expect(highestNumber(graphOf({ 'prd/a.md': PRD }), 'task')).toBe(0)
  })
})

function frontMatterOf(source: string): Record<string, string> {
  const body = source.split('---')[1]!
  return Object.fromEntries(
    body
      .trim()
      .split('\n')
      .map((line) => line.split(/:\s(.*)/) as [string, string]),
  )
}

import { writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { contentHash, findNode, highestNumber, readDocContent, readGraph } from '../src/docRepository.ts'
import { doc, excludeConfig, makeDocsDir, relationEdge, testConfig } from './helpers.ts'

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

/** A spec body carrying requirement items — what a fine-grained relation can point at. */
const SPEC_ITEMS = [
  '# Spec: Whiteboard',
  '',
  '- **spec-00001-FR-1** (Event) the board loads the graph.',
  '- **spec-00001-FR-2** (Unwanted) a broken document is marked.',
  '',
  '- **spec-00001-AC-1.1** (spec-00001-FR-1)',
  '  Given docs',
  '  When the board loads',
  '  Then a node per document',
  '',
].join('\n')
const ITEMISED_SPEC = doc({ id: 'spec-00001-whiteboard', type: 'spec', status: 'active' }, SPEC_ITEMS)
const STALE_ROW = '| spec-00001-AC-99.1 | a stale test | pass |'

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
      relationEdge('prd-00001-whiteboard', 'idea-00001-whiteboard', 'parent'),
      relationEdge('spec-00001-whiteboard', 'prd-00001-whiteboard', 'parent'),
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
      relationEdge('plan-00001-mvp', 'spec-00001-whiteboard', 'implements'),
      relationEdge('plan-00001-mvp', 'design-00001-whiteboard', 'implements'),
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
    expect(graphOf({})).toEqual({ nodes: [], edges: [], issues: [], diagnostics: [], idOwners: {} })
  })

  it('yields an empty graph when the docs directory does not exist', () => {
    expect(readGraph('/nonexistent/docs', config)).toEqual({
      nodes: [],
      edges: [],
      issues: [],
      diagnostics: [],
      idOwners: {},
    })
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
    expect(graph.issues).toEqual([{ path: 'prd/broken.md', nodeId: 'prd/broken.md', message: 'front matter is missing' }])
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
      relationEdge('prd-00001-whiteboard', 'idea-09999-ghost', 'parent', false),
    ])
    expect(graph.issues).toEqual([
      { path: 'prd/b.md', nodeId: 'prd-00001-whiteboard', message: 'parent points at unknown document "idea-09999-ghost"' },
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

  // spec-00001-AC-2.5 — a fine-grained reference is not a break: it lands on the
  // document holding the item (decision-00004 §5 裁定一).
  it('lands a relation naming a requirement item on the document that declares it', () => {
    const graph = graphOf({
      'spec/c.md': ITEMISED_SPEC,
      'record/r.md': doc(
        { id: 'record-00001-acceptance', type: 'record', status: 'active', verifies: '[spec-00001-FR-1]' },
        '# Record\n',
      ),
    })

    expect(graph.edges).toEqual([
      relationEdge('record-00001-acceptance', 'spec-00001-whiteboard', 'verifies', true, ['spec-00001-FR-1']),
    ])
    expect(graph.issues).toEqual([])
  })

  it('lands a relation naming an acceptance criterion on the same document', () => {
    const graph = graphOf({
      'spec/c.md': ITEMISED_SPEC,
      'record/r.md': doc(
        { id: 'record-00001-acceptance', type: 'record', status: 'active', verifies: '[spec-00001-AC-1.1]' },
        '# Record\n',
      ),
    })

    expect(graph.edges).toEqual([
      relationEdge('record-00001-acceptance', 'spec-00001-whiteboard', 'verifies', true, ['spec-00001-AC-1.1']),
    ])
  })

  // spec-00001-AC-2.6 — the document exists, the item does not
  it('marks a relation naming an item that does not exist', () => {
    const graph = graphOf({
      'spec/c.md': ITEMISED_SPEC,
      'record/r.md': doc(
        { id: 'record-00001-acceptance', type: 'record', status: 'active', verifies: '[spec-00001-FR-999]' },
        '# Record\n',
      ),
    })

    expect(graph.edges).toEqual([
      relationEdge('record-00001-acceptance', 'spec-00001-FR-999', 'verifies', false, ['spec-00001-FR-999']),
    ])
    expect(graph.issues).toEqual([
      { path: 'record/r.md', nodeId: 'record-00001-acceptance', message: 'verifies points at unknown document "spec-00001-FR-999"' },
    ])
  })

  // spec-00001-AC-28.5 — three ids along one path are one line, and it carries all three
  it('merges the ids of one field that land on the same document into a single edge', () => {
    const graph = graphOf({
      'spec/c.md': ITEMISED_SPEC,
      'record/r.md': doc(
        {
          id: 'record-00001-acceptance',
          type: 'record',
          status: 'active',
          verifies: '[spec-00001-FR-1, spec-00001-FR-2, spec-00001-AC-1.1]',
        },
        '# Record\n',
      ),
    })

    expect(graph.edges).toEqual([
      relationEdge('record-00001-acceptance', 'spec-00001-whiteboard', 'verifies', true, [
        'spec-00001-FR-1',
        'spec-00001-FR-2',
        'spec-00001-AC-1.1',
      ]),
    ])
  })

  // spec-00001-AC-33.2 — a break inside the body is not a break of the node
  it('keeps a document sound when a record verifies a criterion it does not have', () => {
    const graph = graphOf({
      'spec/c.md': ITEMISED_SPEC,
      'record/r.md': doc(
        { id: 'record-00001-acceptance', type: 'record', status: 'active' },
        ['# Record', '', '| GWT id | 测试 | 结果 |', '| --- | --- | --- |', STALE_ROW, ''].join('\n'),
      ),
    })

    expect(graph.nodes.every((node) => node.ok)).toBe(true)
    expect(graph.issues).toEqual([])
  })

  it('resolves no item of a document whose own front matter is broken', () => {
    const graph = graphOf({
      'spec/c.md': doc({ id: 'spec-00001-whiteboard', type: 'spec' }, SPEC_ITEMS),
      'record/r.md': doc(
        { id: 'record-00001-acceptance', type: 'record', status: 'active', verifies: '[spec-00001-FR-1]' },
        '# Record\n',
      ),
    })

    expect(graph.edges[0]!.ok).toBe(false)
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

  /**
   * rule-00001-BR-18 against the re-keying of spec-00002-FR-8: a colliding
   * document is keyed by its path, which matches no id pattern, so counting
   * node keys would lose the number and hand it out again — two documents
   * sharing an id would become three. The count reads declared ids.
   */
  it('still counts a number two documents collided on', () => {
    const graph = graphOf({
      'prd/a.md': PRD,
      'prd/b.md': doc({ id: 'prd-00007-later', type: 'prd', status: 'active' }),
      'prd/c.md': doc({ id: 'prd-00007-later', type: 'prd', status: 'draft' }, '# Another\n'),
    })

    expect(highestNumber(graph, 'prd')).toBe(7)
  })
})

/**
 * Two documents declaring one id (spec-00002-FR-8, issue-00004). Neither may
 * disappear and neither may be acted on by that id: each becomes a node keyed
 * by its own file path, marked anomalous, pointing at the other.
 */
describe('documents that collide on an id', () => {
  const FIRST = doc({ id: 'spec-00002-clash', type: 'spec', status: 'draft' }, '# The first\n')
  const SECOND = doc({ id: 'spec-00002-clash', type: 'spec', status: 'active' }, '# The second\n')
  const COLLIDING = { 'spec/first.md': FIRST, 'spec/second.md': SECOND }

  // spec-00002-AC-8.1
  it('presents both, each keyed by its own file path and carrying the colliding id', () => {
    const nodes = graphOf(COLLIDING).nodes

    expect(nodes.map((node) => node.id)).toEqual(['spec/first.md', 'spec/second.md'])
    expect(nodes.map((node) => node.duplicateOf)).toEqual(['spec-00002-clash', 'spec-00002-clash'])
    expect(nodes.map((node) => node.title)).toEqual(['The first', 'The second'])
  })

  // spec-00002-AC-8.2
  it('marks both anomalous, each problem naming the other file', () => {
    const nodes = graphOf(COLLIDING).nodes

    expect(nodes.map((node) => node.ok)).toEqual([false, false])
    expect(nodes[0]!.problems).toContain('id "spec-00002-clash" is also declared by spec/second.md')
    expect(nodes[1]!.problems).toContain('id "spec-00002-clash" is also declared by spec/first.md')
  })

  // spec-00002-AC-8.3
  it('presents all three when three documents collide, every one of them anomalous', () => {
    const nodes = graphOf({
      ...COLLIDING,
      'spec/third.md': doc({ id: 'spec-00002-clash', type: 'spec', status: 'draft' }, '# The third\n'),
    }).nodes

    expect(nodes.map((node) => node.id)).toEqual(['spec/first.md', 'spec/second.md', 'spec/third.md'])
    expect(nodes.every((node) => node.ok)).toBe(false)
    expect(nodes.filter((node) => node.ok)).toEqual([])
  })

  // spec-00002-AC-8.6 — the colliding id is nobody's key, so the target is ambiguous
  it('breaks an edge aimed at the colliding id', () => {
    const graph = graphOf({
      ...COLLIDING,
      'plan/p.md': doc({ id: 'plan-00001-p', type: 'plan', status: 'open', parent: 'spec-00002-clash' }, '# P\n'),
    })

    expect(graph.edges).toContainEqual(
      relationEdge('plan-00001-p', 'spec-00002-clash', 'parent', false),
    )
  })

  /**
   * spec-00002-FR-8 and AC-8.7 at the level that exists today: the items in a
   * colliding body are claimed by nobody, which is the one filter the global
   * coverage view of FR-10 will select on (plan-00013). Ambiguous evidence is
   * no evidence.
   */
  it('lets no requirement item of a colliding document be claimed', () => {
    const graph = graphOf({
      'spec/first.md': doc({ id: 'spec-00001-whiteboard', type: 'spec', status: 'active' }, SPEC_ITEMS),
      'spec/second.md': doc({ id: 'spec-00001-whiteboard', type: 'spec', status: 'draft' }, '# The other\n'),
      'plan/p.md': doc(
        { id: 'plan-00001-p', type: 'plan', status: 'open', implements: '[spec-00001-FR-1]' },
        '# P\n',
      ),
    })

    expect(graph.edges).toContainEqual(relationEdge('plan-00001-p', 'spec-00001-FR-1', 'implements', false))
  })

  // spec-00002-AC-8.10
  it('clears the anomaly on the survivor once the other file is gone', () => {
    const nodes = graphOf({ 'spec/first.md': FIRST }).nodes

    expect(nodes.map((node) => node.id)).toEqual(['spec-00002-clash'])
    expect(nodes[0]!.ok).toBe(true)
    expect(nodes[0]!.duplicateOf).toBeUndefined()
  })

  // spec-00002-AC-8.11
  it('clears the anomaly on both once one of them takes a free id', () => {
    const nodes = graphOf({
      'spec/first.md': FIRST,
      'spec/second.md': doc({ id: 'spec-00003-apart', type: 'spec', status: 'active' }, '# The second\n'),
    }).nodes

    expect(nodes.map((node) => node.id)).toEqual(['spec-00002-clash', 'spec-00003-apart'])
    expect(nodes.every((node) => node.ok && node.duplicateOf === undefined)).toBe(true)
  })

  // The document with no id at all is keyed by path too, and collides with nobody
  it('leaves a document that declares no id keyed by its path and alone', () => {
    const nodes = graphOf({ 'spec/none.md': '# No front matter\n' }).nodes

    expect(nodes[0]!.id).toBe('spec/none.md')
    expect(nodes[0]!.duplicateOf).toBeUndefined()
  })
})

/**
 * The resolvable-id table of the inline-id jump (spec-00001-FR-57): document
 * ids off the node keys, item and criterion ids off the bodies. It serves the
 * presentation layer alone — nothing in it feeds an edge or a diagnostic
 * (spec-00001-FR-59).
 */
describe('idOwners', () => {
  // spec-00001-FR-57 — a document id resolves to itself, an item or AC id to its document
  it('maps document ids to themselves and item and AC ids to their document', () => {
    const graph = graphOf({ 'idea/a.md': IDEA, 'spec/c.md': ITEMISED_SPEC })

    expect(graph.idOwners['idea-00001-whiteboard']).toBe('idea-00001-whiteboard')
    expect(graph.idOwners['spec-00001-whiteboard']).toBe('spec-00001-whiteboard')
    expect(graph.idOwners['spec-00001-FR-1']).toBe('spec-00001-whiteboard')
    expect(graph.idOwners['spec-00001-AC-1.1']).toBe('spec-00001-whiteboard')
  })

  // spec-00001-AC-58.5 — a collided id is an ambiguous target, so it enters nowhere
  it('keeps a colliding id and the items behind it out of the table', () => {
    const graph = graphOf({
      'spec/first.md': doc({ id: 'spec-00001-whiteboard', type: 'spec', status: 'active' }, SPEC_ITEMS),
      'spec/second.md': doc({ id: 'spec-00001-whiteboard', type: 'spec', status: 'draft' }, '# The other\n'),
    })

    // The nodes are path-keyed (spec-00002-FR-8) and a path is not an id, so
    // the table is empty rather than pointing anywhere.
    expect(graph.idOwners).toEqual({})
  })

  // spec-00001-FR-57 — an anomalous document is still a jump target; its items claim nothing
  it('keeps an anomalous document reachable while its items stay unclaimed', () => {
    const graph = graphOf({
      'spec/bad.md': doc({ id: 'spec-00001-whiteboard', type: 'spec', status: 'bogus' }, SPEC_ITEMS),
    })

    expect(graph.idOwners['spec-00001-whiteboard']).toBe('spec-00001-whiteboard')
    expect(graph.idOwners['spec-00001-FR-1']).toBeUndefined()
  })

  it('gives a document with no id no entry at all', () => {
    expect(graphOf({ 'spec/none.md': '# No front matter\n' }).idOwners).toEqual({})
  })

  // spec-00001-AC-59.1 — a prose reference is in the table and nowhere else
  it('feeds no edge and no diagnostic for an id referenced in prose', () => {
    const reader = doc(
      { id: 'spec-00002-reader', type: 'spec', status: 'active' },
      '# Spec: Reader\n\n- **spec-00002-FR-1** (Event) applies `spec-00001-FR-1` as written.\n',
    )
    const graph = graphOf({ 'spec/c.md': ITEMISED_SPEC, 'spec/r.md': reader })

    expect(graph.edges).toEqual([])
    expect(graph.diagnostics).toEqual([])
    expect(graph.idOwners['spec-00001-FR-1']).toBe('spec-00001-whiteboard')
  })
})

/**
 * The scan exclusions of spec-00010-FR-1: a file a pattern matches does not exist
 * for the board. Everything below the first two cases is a downstream consequence
 * of that one omission and of nothing else — design-00001 §14.3 names them one by
 * one precisely because no code carries them.
 */
describe('files an exclude pattern matches', () => {
  const SOURCE_PATTERN = 'reference/*/source/**'
  const SUMMARY = doc({ id: 'reference-00001-stripe', type: 'reference', status: 'active' }, '# Stripe\n')
  const RAW = '# Stripe Webhooks\n\nscraped material, no front matter\n'
  const CORPUS = { 'reference/stripe/source/a.md': RAW, 'reference/stripe/summary.md': SUMMARY }

  function graphWith(files: Record<string, string>, patterns?: string[]) {
    return readGraph(makeDocsDir(files), excludeConfig(patterns))
  }

  // spec-00010-AC-1.1
  it('keeps a matched file out of the nodes, the issues, and the diagnostics', () => {
    const graph = graphWith(CORPUS, [SOURCE_PATTERN])

    expect(graph.nodes.map((node) => node.path)).toEqual(['reference/stripe/summary.md'])
    expect(graph.issues).toEqual([])
    expect(graph.diagnostics).toEqual([])
  })

  // spec-00010-AC-1.2
  it('reads the same file as an anomalous node keyed by its path when no pattern is configured', () => {
    const graph = graphWith(CORPUS)

    const raw = graph.nodes.find((node) => node.path === 'reference/stripe/source/a.md')!
    expect(raw.id).toBe('reference/stripe/source/a.md')
    expect(raw.ok).toBe(false)
    expect(graph.issues).toEqual([
      { path: 'reference/stripe/source/a.md', nodeId: 'reference/stripe/source/a.md', message: 'front matter is missing' },
    ])
  })

  // spec-00010-AC-1.3
  it('gives back the same graph after a matched file changes on disk', () => {
    const docsDir = makeDocsDir(CORPUS)
    const config = excludeConfig([SOURCE_PATTERN])
    const before = readGraph(docsDir, config)

    writeFileSync(join(docsDir, 'reference/stripe/source/a.md'), doc({ id: 'reference-00099-a', type: 'reference', status: 'draft' }, '# Rewritten\n'))

    expect(readGraph(docsDir, config)).toEqual(before)
  })

  // spec-00010-AC-1.4 — the template filter and the glob filter are two filters, not one
  it('leaves out a README the glob does not reach and one it reaches too', () => {
    const graph = graphWith(
      { ...CORPUS, 'reference/stripe/README.md': '# Stripe\n', 'reference/stripe/source/README.md': '# Source\n' },
      [SOURCE_PATTERN],
    )

    expect(graph.nodes.map((node) => node.path)).toEqual(['reference/stripe/summary.md'])
  })

  /**
   * spec-00010-AC-1.5 at the level it is decided: the command palette matches over
   * the graph's nodes (`matchDocuments`), so a title that is on no node is a title
   * nothing can search up.
   */
  // spec-00010-AC-1.5
  it('puts the title of a matched file on no node for the command palette to find', () => {
    const graph = graphWith(CORPUS, [SOURCE_PATTERN])

    expect(graph.nodes.map((node) => node.title)).toEqual(['Stripe'])
    expect(graph.nodes.some((node) => node.title === 'Stripe Webhooks')).toBe(false)
  })

  // spec-00010-AC-1.8 — `*` matches within one segment and does not cross `/`
  it('matches a single segment with `*` and no deeper', () => {
    const graph = graphWith(
      {
        'reference/stripe/notes.md': doc({ id: 'reference-00001-notes', type: 'reference', status: 'active' }),
        'reference/stripe/source/notes.md': doc({ id: 'reference-00002-deep', type: 'reference', status: 'active' }),
      },
      ['reference/*/notes.md'],
    )

    expect(graph.nodes.map((node) => node.path)).toEqual(['reference/stripe/source/notes.md'])
  })

  // spec-00010-AC-1.9 — a directory-shaped pattern matches the directory, not the files under it
  it('matches nothing for a pattern naming a directory', () => {
    const graph = graphWith(
      {
        'reference/stripe/summary.md': SUMMARY,
        'reference/stripe/webhooks.md': doc({ id: 'reference-00002-hooks', type: 'reference', status: 'active' }),
      },
      ['reference/stripe'],
    )

    expect(graph.nodes.map((node) => node.path)).toEqual([
      'reference/stripe/summary.md',
      'reference/stripe/webhooks.md',
    ])
    expect(graph.nodes.every((node) => node.ok)).toBe(true)
  })

  // spec-00010-AC-1.10 — matching everything is no error and raises no prompt of its own
  it('yields the empty graph, and no issue, for a pattern matching everything', () => {
    expect(graphWith(CORPUS, ['**'])).toEqual({ nodes: [], edges: [], issues: [], diagnostics: [], idOwners: {} })
  })

  // spec-00010-AC-1.11 — matching nothing is no error either
  it('yields the graph it would without any pattern for one matching nothing', () => {
    const docsDir = makeDocsDir(CORPUS)

    expect(readGraph(docsDir, excludeConfig(['nowhere/**']))).toEqual(readGraph(docsDir, excludeConfig()))
  })
})

/**
 * The ids a matched file declares are declared by nobody the board can see
 * (spec-00010-FR-3, FR-11): the edges aimed at them break where they were
 * declared, the inline-id table has no entry for them, and two matched files
 * colliding on an id collide in no graph at all.
 */
describe('ids only an excluded file declares', () => {
  const SOURCE_PATTERN = 'reference/*/source/**'
  const EXCLUDED_REFERENCE = doc({ id: 'reference-00099-stripe-b', type: 'reference', status: 'draft' }, '# B\n')
  const DESIGN = doc(
    { id: 'design-00002-ui', type: 'design', status: 'active', informs: '[reference-00099-stripe-b]' },
    '# UI\n',
  )
  const ARCHIVED_SPEC = doc(
    { id: 'spec-00042-old', type: 'spec', status: 'active' },
    '# Old\n\n- **spec-00042-FR-1** (Event) the old boundary.\n',
  )
  const RECORD = doc(
    { id: 'record-00001-acceptance', type: 'record', status: 'active', verifies: '[spec-00042-FR-1]' },
    '# Record\n',
  )

  const BROKEN_LINK = {
    'design/ui.md': DESIGN,
    'reference/stripe/source/b.md': EXCLUDED_REFERENCE,
  }
  const ARCHIVE = { 'spec/archive/spec-00042-old.md': ARCHIVED_SPEC, 'record/r.md': RECORD }

  function graphWith(files: Record<string, string>, patterns: string[]) {
    return readGraph(makeDocsDir(files), excludeConfig(patterns))
  }

  // spec-00010-AC-3.1
  it('breaks a relation edge into an excluded document and files the issue on the declaring one', () => {
    const graph = graphWith(BROKEN_LINK, [SOURCE_PATTERN])

    expect(graph.edges).toEqual([relationEdge('design-00002-ui', 'reference-00099-stripe-b', 'informs', false)])
    expect(graph.issues).toEqual([
      {
        path: 'design/ui.md',
        nodeId: 'design-00002-ui',
        message: 'informs points at unknown document "reference-00099-stripe-b"',
      },
    ])
  })

  // spec-00010-AC-3.2 — the item id of an excluded spec is owned by nobody either
  it('breaks a record’s verifies into an item only an excluded spec declares', () => {
    const graph = graphWith(ARCHIVE, ['spec/archive/**'])

    expect(graph.edges).toEqual([relationEdge('record-00001-acceptance', 'spec-00042-FR-1', 'verifies', false)])
    expect(graph.issues).toEqual([
      {
        path: 'record/r.md',
        nodeId: 'record-00001-acceptance',
        message: 'verifies points at unknown document "spec-00042-FR-1"',
      },
    ])
  })

  /**
   * spec-00010-AC-3.3 at the level the server decides it: `idOwners` is the one
   * basis of the inline-id jump's clickability (spec-00001-FR-57), so an id with
   * no entry there is an id the panel renders unclickable. The rendering itself is
   * the page's (web/test).
   */
  // spec-00010-AC-3.3
  it('keeps an item id only an excluded spec declares out of idOwners', () => {
    const graph = graphWith(ARCHIVE, ['spec/archive/**'])

    expect(graph.idOwners['spec-00042-FR-1']).toBeUndefined()
    expect(graph.idOwners['spec-00042-old']).toBeUndefined()
  })

  // spec-00010-AC-3.4 — a second read is the same read
  it('breaks the same edge, once, on a second read', () => {
    const docsDir = makeDocsDir(BROKEN_LINK)
    const config = excludeConfig([SOURCE_PATTERN])
    const first = readGraph(docsDir, config)

    const second = readGraph(docsDir, config)

    expect(second).toEqual(first)
    expect(second.edges.filter((edge) => !edge.ok)).toHaveLength(1)
    expect(second.issues).toHaveLength(1)
  })

  // spec-00010-AC-11.1 — an excluded file is in nobody's collision set
  it('leaves a visible document sole owner of an id an excluded file also declares', () => {
    const graph = graphWith(
      { ...BROKEN_LINK, 'reference/reference-00099-stripe-b.md': EXCLUDED_REFERENCE },
      [SOURCE_PATTERN],
    )

    const node = graph.nodes.find((one) => one.path === 'reference/reference-00099-stripe-b.md')!
    expect(node.id).toBe('reference-00099-stripe-b')
    expect(node.duplicateOf).toBeUndefined()
    expect(node.ok).toBe(true)
    expect(graph.edges).toEqual([relationEdge('design-00002-ui', 'reference-00099-stripe-b', 'informs')])
    expect(graph.issues).toEqual([])
  })

  // spec-00010-AC-11.2 — two excluded files colliding on an id collide nowhere
  it('reports nothing at all for two excluded files declaring one id', () => {
    const graph = graphWith(
      {
        'reference/stripe/source/b.md': doc({ id: 'reference-00099-dup', type: 'reference', status: 'draft' }),
        'reference/ccbill/source/c.md': doc({ id: 'reference-00099-dup', type: 'reference', status: 'draft' }),
      },
      [SOURCE_PATTERN],
    )

    expect(graph.nodes).toEqual([])
    expect(graph.issues).toEqual([])
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

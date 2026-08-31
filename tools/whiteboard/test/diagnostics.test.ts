import { describe, expect, it } from 'vitest'
import { readGraph } from '../src/docRepository.ts'
import { doc, makeDocsDir, testConfig } from './helpers.ts'

/**
 * The parse diagnostics of spec-00001-FR-40 as the whole board sees them: one
 * derivation for the tree, so the count in the top bar and the rows in the
 * inspector are the same finding twice, never two readings.
 */
const config = testConfig()

function graphOf(files: Record<string, string>) {
  return readGraph(makeDocsDir(files), config)
}

const SPEC = doc(
  { id: 'spec-00001-x', type: 'spec', status: 'active' },
  [
    '# Spec X',
    '',
    '- **spec-00001-FR-1** (Event) the first requirement',
    '',
    '**Acceptance (GWT)**',
    '',
    '- **spec-00001-AC-1.1** (spec-00001-FR-1)',
    '  Given a board When it loads Then it works',
    '',
  ].join('\n'),
)

/** The same spec with a second requirement that lost its list marker. */
const SPEC_WITH_DRIFT = SPEC.replace(
  '**Acceptance (GWT)**',
  ['**spec-00001-FR-2** (Event) 掉了列表符号。', '', '**Acceptance (GWT)**'].join('\n'),
)

const PASSING_RECORD = doc(
  { id: 'record-00001-x', type: 'record', status: 'active' },
  ['# Record', '', '| GWT id | 测试 | 结果 |', '| --- | --- | --- |', '| spec-00001-AC-1.1 | a test | pass |', ''].join(
    '\n',
  ),
)

const RANGE_RECORD = PASSING_RECORD.replace(
  '| spec-00001-AC-1.1 | a test | pass |',
  '| spec-00001-AC-1.1 … AC-9.2 | nine tests | pass |',
)

describe('the diagnostics of the whole tree', () => {
  // spec-00001-AC-40.5 — the state every document in this repo is meant to be in
  it('reports nothing when every document follows the grammar', () => {
    const graph = graphOf({ 'spec/a.md': SPEC, 'record/r.md': PASSING_RECORD })

    expect(graph.diagnostics).toEqual([])
    expect(graph.issues).toEqual([])
  })

  it('names the document each diagnostic belongs to — the spec for its own line', () => {
    const graph = graphOf({ 'spec/a.md': SPEC_WITH_DRIFT, 'record/r.md': PASSING_RECORD })

    expect(graph.diagnostics).toEqual([
      {
        docId: 'spec-00001-x',
        kind: 'item-shape',
        declaredId: 'spec-00001-FR-2',
        // Counted in the body, front matter excluded — what the AST sees.
        line: 6,
        text: '**spec-00001-FR-2** (Event) 掉了列表符号。',
      },
    ])
  })

  // The record holds the line, but the spec is what the row was reaching for
  it('names the verified document for a malformed checklist row, and the record it came from', () => {
    const graph = graphOf({ 'spec/a.md': SPEC, 'record/r.md': RANGE_RECORD })

    expect(graph.diagnostics).toMatchObject([
      { docId: 'spec-00001-x', kind: 'checklist-row', recordId: 'record-00001-x' },
    ])
  })

  // spec-00001-AC-40.4 — a diagnostic is a reading that drifted, not a broken document
  it('leaves the node sound and the other items` coverage untouched', () => {
    const graph = graphOf({ 'spec/a.md': SPEC_WITH_DRIFT, 'record/r.md': PASSING_RECORD })
    const spec = graph.nodes.find((node) => node.id === 'spec-00001-x')!

    expect(spec.ok).toBe(true)
    expect(spec.problems).toEqual([])
    expect(graph.issues).toEqual([])
  })

  // spec-00001-AC-40.3, the data side: two counts, neither derived from the other
  it('counts apart from the anomalies, which count apart from it', () => {
    const graph = graphOf({
      'spec/a.md': SPEC_WITH_DRIFT,
      'record/r.md': PASSING_RECORD,
      'prd/broken.md': '# No front matter at all\n',
    })

    expect(graph.diagnostics).toHaveLength(1)
    expect(graph.issues).toEqual([{ path: 'prd/broken.md', nodeId: 'prd/broken.md', message: 'front matter is missing' }])
  })

  it('reads no items and so no diagnostics from a type that declares none', () => {
    expect(graphOf({ 'idea/a.md': doc({ id: 'idea-00001-x', type: 'idea', status: 'draft' }, SPEC_WITH_DRIFT) }))
      .toMatchObject({ diagnostics: [] })
  })
})

/**
 * The relation-field diagnostics of spec-00002-FR-5 … FR-7: whether a relation
 * field belongs to a type is answered by the matrix in the flow config and by
 * nothing else — the board does not read the prose in a folder README. The
 * finding is a diagnostic, never an anomaly: how a field is written is drift,
 * not damage, and a node that drifts keeps every one of its actions.
 */
describe('the relation matrix diagnostics', () => {
  const MATRIX = `
carries:
  spec: [parent]
  design: [informs]
  record: [parent, verifies]
  idea: []
`
  const matrixConfig = testConfig(MATRIX)

  function matrixGraph(files: Record<string, string>) {
    return readGraph(makeDocsDir(files), matrixConfig)
  }

  function relationFieldText(files: Record<string, string>): string[] {
    return matrixGraph(files)
      .diagnostics.filter((diagnostic) => diagnostic.kind === 'relation-field')
      .map((diagnostic) => diagnostic.text!)
  }

  // spec-00002-AC-5.1
  it('passes a field its type is allowed to carry', () => {
    const files = { 'design/d.md': doc({ id: 'design-00001-d', type: 'design', status: 'active', informs: '[spec-00001-x]' }, '# D\n') }

    expect(relationFieldText(files)).toEqual([])
  })

  // spec-00002-AC-5.2 and AC-7.1
  it('reports a field its type does not carry, naming the field, the type and the document', () => {
    const graph = matrixGraph({
      'record/r.md': doc(
        { id: 'record-00001-r', type: 'record', status: 'active', implements: '[spec-00001-FR-1]' },
        '# Record\n',
      ),
    })

    expect(graph.diagnostics).toEqual([
      {
        docId: 'record-00001-r',
        kind: 'relation-field',
        // Front matter has no body line to point at, so the row carries none.
        text: 'implements is not a relation field a record document carries',
      },
    ])
  })

  // spec-00002-AC-5.3 — an empty list is the matrix saying «this type carries nothing»
  it('reports any relation field on a type whose allowed set is empty', () => {
    const files = {
      'idea/i.md': doc({ id: 'idea-00001-i', type: 'idea', status: 'draft', motivated_by: '[prd-00001-x]' }, '# I\n'),
    }

    expect(relationFieldText(files)).toEqual(['motivated_by is not a relation field a idea document carries'])
  })

  // spec-00002-AC-5.4 — opting in is per type, so a type left out is not checked
  it('checks nothing about a type the matrix does not list', () => {
    const files = {
      'plan/p.md': doc({ id: 'plan-00001-p', type: 'plan', status: 'open', implements: '[spec-00001-x]', blocks: '[issue-00001-i]' }, '# P\n'),
    }

    expect(relationFieldText(files)).toEqual([])
  })

  // The ruling of design-00001 §2: docs/README.md grants supersedes to every type
  it('allows supersedes on a type whose list does not mention it', () => {
    const files = {
      'spec/s.md': doc({ id: 'spec-00002-s', type: 'spec', status: 'active', supersedes: '[spec-00001-x]' }, '# S\n'),
    }

    expect(relationFieldText(files)).toEqual([])
  })

  // spec-00002-AC-6.4 — no matrix, no check, whatever the documents declare
  it('reports nothing at all when the flow config carries no matrix', () => {
    const mismatched = doc(
      { id: 'record-00001-r', type: 'record', status: 'active', implements: '[spec-00001-FR-1]' },
      '# Record\n',
    )

    expect(graphOf({ 'record/r.md': mismatched }).diagnostics).toEqual([])
  })

  // spec-00002-AC-7.3 — single-valued `parent` is held by the code, not the matrix
  it('reports a parent declared with two ids, naming parent as single-valued', () => {
    const files = {
      'spec/s.md': doc({ id: 'spec-00001-s', type: 'spec', status: 'active', parent: '[prd-00001-x, prd-00002-y]' }, '# S\n'),
    }

    expect(relationFieldText(files)).toEqual(['parent is a single-valued field, but this spec declares 2 ids'])
  })

  // A one-element list reads as a single value — FR-7 says «multi-valued»
  it('says nothing about a parent written as a one-element list', () => {
    const files = {
      'spec/s.md': doc({ id: 'spec-00001-s', type: 'spec', status: 'active', parent: '[prd-00001-x]' }, '# S\n'),
    }

    expect(relationFieldText(files)).toEqual([])
  })

  // spec-00002-AC-7.2 — the node keeps its health, its edges and its coverage
  it('leaves the node sound, its edges drawn and the anomaly list empty', () => {
    const graph = matrixGraph({
      'spec/s.md': doc({ id: 'spec-00001-s', type: 'spec', status: 'active' }, '# S\n'),
      'record/r.md': doc(
        { id: 'record-00001-r', type: 'record', status: 'active', implements: '[spec-00001-s]' },
        '# Record\n',
      ),
    })
    const record = graph.nodes.find((node) => node.id === 'record-00001-r')!

    expect(record.ok).toBe(true)
    expect(record.problems).toEqual([])
    expect(graph.issues).toEqual([])
    expect(graph.edges).toContainEqual(
      expect.objectContaining({ from: 'record-00001-r', to: 'spec-00001-s', relation: 'implements', ok: true }),
    )
  })

  // spec-00002-AC-7.4 — one document off the matrix, one diagnostic in the count
  it('counts as one diagnostic in a tree that otherwise has none', () => {
    const graph = matrixGraph({
      'spec/a.md': SPEC,
      'record/r.md': doc(
        { id: 'record-00001-x', type: 'record', status: 'active', implements: '[spec-00001-x]' },
        ['# Record', '', '| GWT id | 测试 | 结果 |', '| --- | --- | --- |', '| spec-00001-AC-1.1 | a test | pass |', ''].join('\n'),
      ),
    })

    expect(graph.diagnostics).toHaveLength(1)
  })

  // A document whose type will not parse has no matrix entry to be read against
  it('checks nothing about a file whose front matter declares no type', () => {
    expect(relationFieldText({ 'spec/broken.md': '# No front matter at all\n' })).toEqual([])
  })
})

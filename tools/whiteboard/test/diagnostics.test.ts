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
    expect(graph.issues).toEqual([{ path: 'prd/broken.md', message: 'front matter is missing' }])
  })

  it('reads no items and so no diagnostics from a type that declares none', () => {
    expect(graphOf({ 'idea/a.md': doc({ id: 'idea-00001-x', type: 'idea', status: 'draft' }, SPEC_WITH_DRIFT) }))
      .toMatchObject({ diagnostics: [] })
  })
})

import { describe, expect, it } from 'vitest'
import type { DocBody } from '../src/requirements.ts'
import { deliveryScope, itemCoverage, resolvedGaps } from '../src/resolvedGate.ts'

/**
 * spec-00001-FR-52 with rule-00001-BR-24 and BR-25, at the derivation itself: the
 * scope a plan's `implements` declares, and the verdict over one evidence set.
 */

/** A spec or rule body in the shape those folders use: items as list entries, criteria attributed. */
function body(items: string[], criteria: string[] = []): string {
  return ['## 4. Requirements', '', ...items, '', '## 5. Acceptance (GWT)', '', ...criteria, ''].join('\n')
}

function item(id: string): string {
  return `- **${id}** (Event) the system shall do the thing`
}

function criterion(id: string, itemId: string): string {
  return `- **${id}** (${itemId})\n  Given a board\n  When it loads\n  Then it works`
}

/** A record's acceptance checklist, the shape `docs/record/README.md` prescribes. */
function checklist(recordId: string, rows: [string, string][]): DocBody {
  return {
    id: recordId,
    body: [
      '# 验收记录',
      '',
      '| GWT id | 测试 | 结果 |',
      '| --- | --- | --- |',
      ...rows.map(([target, result]) => `| ${target} | some.test.ts | ${result} |`),
      '',
    ].join('\n'),
  }
}

const SPEC: DocBody = {
  id: 'spec-00001-board',
  body: body(
    [item('spec-00001-FR-1'), item('spec-00001-FR-2')],
    [
      criterion('spec-00001-AC-1.1', 'spec-00001-FR-1'),
      criterion('spec-00001-AC-1.2', 'spec-00001-FR-1'),
      criterion('spec-00001-AC-2.1', 'spec-00001-FR-2'),
    ],
  ),
}

const RULE: DocBody = {
  id: 'rule-00001-flow',
  body: body(
    [item('rule-00001-BR-1'), item('rule-00001-BR-2'), item('rule-00001-BR-3')],
    [
      criterion('rule-00001-AC-1.1', 'rule-00001-BR-1'),
      criterion('rule-00001-AC-2.1', 'rule-00001-BR-2'),
      criterion('rule-00001-AC-3.1', 'rule-00001-BR-3'),
    ],
  ),
}

const DOC_IDS = ['spec-00001-board', 'rule-00001-flow', 'design-00001-board', 'plan-00001-mvp']

/** The item documents read against an evidence set; no records means no coverage at all. */
function docsWith(...records: DocBody[]) {
  return itemCoverage([SPEC, RULE], records)
}

describe('deliveryScope', () => {
  const docs = docsWith()

  // rule-00001-AC-24.1
  it('takes an item id itself and passes over a design target', () => {
    const scope = deliveryScope(['spec-00001-FR-1', 'design-00001-board'], DOC_IDS, docs)
    expect(scope).toEqual({ items: ['spec-00001-FR-1'], unresolved: [] })
  })

  // rule-00001-AC-24.2
  it('takes every item of a whole rule document', () => {
    const scope = deliveryScope(['rule-00001-flow'], DOC_IDS, docs)
    expect(scope.items).toEqual(['rule-00001-BR-1', 'rule-00001-BR-2', 'rule-00001-BR-3'])
  })

  // rule-00001-AC-24.3
  it('is empty for a plan that implements only a design document', () => {
    expect(deliveryScope(['design-00001-board'], DOC_IDS, docs)).toEqual({ items: [], unresolved: [] })
  })

  // rule-00001-AC-24.4
  it('takes the owning item of an acceptance criterion', () => {
    expect(deliveryScope(['spec-00001-AC-1.2'], DOC_IDS, docs).items).toEqual(['spec-00001-FR-1'])
  })

  // rule-00001-AC-25.5, the resolution half: an id that names nothing in the repo
  it('reports a target that is neither a document nor an item', () => {
    const scope = deliveryScope(['spec-00001-FR-1', 'spec-00001-FR-99'], DOC_IDS, docs)

    expect(scope.items).toEqual(['spec-00001-FR-1'])
    expect(scope.unresolved).toEqual(['spec-00001-FR-99'])
  })

  it('counts an item reached twice once', () => {
    const scope = deliveryScope(['spec-00001-board', 'spec-00001-FR-1', 'spec-00001-AC-2.1'], DOC_IDS, docs)
    expect(scope.items).toEqual(['spec-00001-FR-1', 'spec-00001-FR-2'])
  })

  it('is empty for a plan that implements nothing at all', () => {
    expect(deliveryScope([], DOC_IDS, docs)).toEqual({ items: [], unresolved: [] })
  })
})

describe('resolvedGaps', () => {
  const PASSING = checklist('record-00001-r', [
    ['spec-00001-AC-1.1', 'pass'],
    ['spec-00001-AC-1.2', 'pass'],
  ])

  // rule-00001-AC-25.1
  it('finds no gap when every criterion of every scope item has a passing row', () => {
    expect(resolvedGaps(['spec-00001-FR-1'], DOC_IDS, docsWith(PASSING))).toEqual([])
  })

  // rule-00001-AC-25.2
  it('names the item when one of its criteria has no row at all', () => {
    const records = checklist('record-00001-r', [['spec-00001-AC-1.1', 'pass']])
    expect(resolvedGaps(['spec-00001-FR-1'], DOC_IDS, docsWith(records))).toEqual(['spec-00001-FR-1'])
  })

  // rule-00001-AC-25.3
  it('names the item when a row of one of its criteria did not pass', () => {
    const records = checklist('record-00001-r', [
      ['spec-00001-AC-1.1', 'pass'],
      ['spec-00001-AC-1.2', 'fail'],
    ])
    expect(resolvedGaps(['spec-00001-FR-1'], DOC_IDS, docsWith(records))).toEqual(['spec-00001-FR-1'])
  })

  // rule-00001-AC-25.5
  it('names an unresolvable target as a gap of its own', () => {
    const gaps = resolvedGaps(['spec-00001-FR-1', 'rule-00001-BR-99'], DOC_IDS, docsWith(PASSING))
    expect(gaps).toEqual(['rule-00001-BR-99'])
  })

  // rule-00001-AC-25.6
  it('finds no gap when the scope is empty, whatever the records say', () => {
    expect(resolvedGaps(['design-00001-board'], DOC_IDS, docsWith())).toEqual([])
  })

  // rule-00001-AC-25.7 — the evidence is the union of the records handed in
  it('takes the coverage of two records together', () => {
    const docs = docsWith(
      checklist('record-00001-r', [['spec-00001-AC-1.1', 'pass']]),
      checklist('record-00002-s', [['spec-00001-AC-1.2', 'pass']]),
    )
    expect(resolvedGaps(['spec-00001-FR-1'], DOC_IDS, docs)).toEqual([])
  })

  // spec-00001-AC-52.7 at the derivation: a whole document in scope, one item short
  it('names the one item of a whole document in scope that nothing verifies', () => {
    const docs = docsWith(
      checklist('record-00001-r', [
        ['rule-00001-AC-1.1', 'pass'],
        ['rule-00001-AC-3.1', 'pass'],
      ]),
    )
    expect(resolvedGaps(['rule-00001-flow'], DOC_IDS, docs)).toEqual(['rule-00001-BR-2'])
  })

  /**
   * The gate reads coverage exactly as `/items` does (design-00001 §2): an item
   * with no criterion at all is a gap, never a pass by silence.
   */
  it('names an item that declares no acceptance criterion at all', () => {
    const docs = itemCoverage([{ id: 'spec-00002-thin', body: body([item('spec-00002-FR-1')]) }], [])
    expect(resolvedGaps(['spec-00002-FR-1'], ['spec-00002-thin'], docs)).toEqual(['spec-00002-FR-1'])
  })
})

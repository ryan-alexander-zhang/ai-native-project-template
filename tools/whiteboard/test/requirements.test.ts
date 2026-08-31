import { describe, expect, it } from 'vitest'
import { type DocBody, acceptanceRows, declaredIds, requirementView } from '../src/requirements.ts'

/** A spec body in the shape spec-00001 uses: items as list entries, criteria attributed in parentheses. */
function specBody(items: string[], criteria: string[] = []): string {
  return ['## 4. System Requirements', '', ...items, '', '**Acceptance (GWT)**', '', ...criteria, ''].join('\n')
}

function item(id: string, text = 'the system shall do the thing'): string {
  return `- **${id}** (Event) ${text}`
}

function criterion(id: string, itemId: string): string {
  return `- **${id}** (${itemId})\n  Given a board\n  When it loads\n  Then it works`
}

const DEFAULT_HEADER = '| GWT id | 测试 | 结果 |'

/** A record's acceptance checklist, the shape `docs/record/README.md` prescribes. */
function checklist(recordId: string, rows: [string, string, string][], header = DEFAULT_HEADER): DocBody {
  return {
    id: recordId,
    body: [
      '# 验收记录',
      '',
      header,
      `| ${'--- | '.repeat(header.split('|').length - 2).trim()}`,
      ...rows.map(([target, test, result]) => `| ${target} | ${test} | ${result} |`),
      '',
    ].join('\n'),
  }
}

const SPEC_ID = 'spec-00001-docs-whiteboard'

function viewOf(body: string, records: DocBody[] = []) {
  return requirementView({ id: SPEC_ID, body }, records)
}

describe('parsing requirement items', () => {
  // spec-00001-FR-31, the data side: list items with their text and AC count
  it('reads every item declared as a list entry, in number order, with its text', () => {
    const view = viewOf(specBody([item('spec-00001-FR-2', 'second'), item('spec-00001-FR-1', 'first')]))

    expect(view.items.map((found) => found.id)).toEqual(['spec-00001-FR-1', 'spec-00001-FR-2'])
    expect(view.items[0]!.text).toBe('(Event) first')
  })

  it('folds the indented continuation lines into the item text', () => {
    const view = viewOf(specBody(['- **spec-00001-FR-1** (Event) first line', '  second line', '', 'unrelated prose']))

    expect(view.items[0]!.text).toBe('(Event) first line second line')
  })

  // rule-00001-BR-2 … BR-9 are declared as decision-table rows, not list entries
  it('reads an item declared as a decision-table row', () => {
    const view = requirementView(
      {
        id: 'rule-00001-docs-workflow',
        body: [
          '| # | 种类 | 当前状态 | 允许的目标状态 |',
          '| --- | --- | --- | --- |',
          '| **rule-00001-BR-2** | living doc | `draft` | `active`、`archived` |',
          '',
          '- **rule-00001-BR-10** (Definition) 接收：对 `draft` 文档的促进。',
        ].join('\n'),
      },
      [],
    )

    expect(view.items.map((found) => found.id)).toEqual(['rule-00001-BR-2', 'rule-00001-BR-10'])
    expect(view.items[0]!.text).toBe('living doc | draft | active、archived')
  })

  it('ignores requirement ids quoted from another document', () => {
    const view = viewOf(specBody([item('spec-00001-FR-1'), item('rule-00001-BR-1')]))

    expect(view.items.map((found) => found.id)).toEqual(['spec-00001-FR-1'])
  })

  it('attributes each criterion to the item its annotation names', () => {
    const view = viewOf(
      specBody(
        [item('spec-00001-FR-1'), item('spec-00001-FR-2')],
        [criterion('spec-00001-AC-1.2', 'spec-00001-FR-1'), criterion('spec-00001-AC-1.1', 'spec-00001-FR-1')],
      ),
    )

    expect(view.items[0]!.criteria.map((found) => found.id)).toEqual(['spec-00001-AC-1.1', 'spec-00001-AC-1.2'])
    expect(view.items[0]!.criteria[0]!.text).toContain('Given a board')
    expect(view.items[1]!.criteria).toEqual([])
  })

  it('offers the item and criterion ids of a document as its declared ids', () => {
    const ids = declaredIds({
      id: SPEC_ID,
      body: specBody([item('spec-00001-FR-1')], [criterion('spec-00001-AC-1.1', 'spec-00001-FR-1')]),
    })

    expect(ids).toEqual(['spec-00001-FR-1', 'spec-00001-AC-1.1'])
  })

  it('reads nothing from a document whose id is not a document id', () => {
    expect(declaredIds({ id: 'spec/broken.md', body: specBody([item('spec-00001-FR-1')]) })).toEqual([])
    expect(requirementView({ id: 'spec/broken.md', body: specBody([item('spec-00001-FR-1')]) }, [])).toEqual({
      items: [],
      diagnostics: [],
    })
  })
})

describe('reading acceptance rows', () => {
  // spec-00001-FR-32: a checklist row is a row of a table that has a test and a result column
  it('reads the record id, the verified id, the test, and the result', () => {
    const rows = acceptanceRows(checklist('record-00001-x', [['spec-00001-AC-1.1', 'draws a node', 'pass']]))

    expect(rows).toEqual([
      { recordId: 'record-00001-x', targetId: 'spec-00001-AC-1.1', test: 'draws a node', result: 'pass' },
    ])
  })

  // An Evidence column neither adds nor removes a row (FR-32); what it does add
  // is the field the detail panel reads (design-00001 §7, spec-00001-FR-37).
  it('reads a checklist with an evidence column just the same, and carries the evidence', () => {
    const rows = acceptanceRows({
      id: 'record-00001-x',
      body: [
        '| GWT / requirement id | Test | Result | Evidence |',
        '| --- | --- | --- | --- |',
        '| spec-00001-AC-1.1 | draws a node | pass | screenshot |',
      ].join('\n'),
    })

    expect(rows).toEqual([
      {
        recordId: 'record-00001-x',
        targetId: 'spec-00001-AC-1.1',
        test: 'draws a node',
        result: 'pass',
        evidence: 'screenshot',
      },
    ])
  })

  // Absent, not empty: nothing to show is not a field showing nothing.
  it('leaves the evidence out when the column is empty, and when there is none', () => {
    const [empty] = acceptanceRows({
      id: 'record-00001-x',
      body: [
        '| GWT id | Test | Result | Evidence |',
        '| --- | --- | --- | --- |',
        '| spec-00001-AC-1.1 | draws a node | pass |  |',
      ].join('\n'),
    })
    const [none] = acceptanceRows(checklist('record-00001-x', [['spec-00001-AC-1.1', 'draws a node', 'pass']]))

    expect(empty).not.toHaveProperty('evidence')
    expect(none).not.toHaveProperty('evidence')
  })

  // the amendment table of a record: ids and prose, no test and no result column
  it('does not read a table of ids and prose as a checklist', () => {
    const rows = acceptanceRows({
      id: 'record-00003-x',
      body: [
        '| GWT id | 变化 |',
        '| --- | --- |',
        '| spec-00001-AC-2.4 | 异常节点的工具栏改为编辑 + 关系列表 |',
        '',
        '| GWT id | 原证据 | 现证据 |',
        '| --- | --- | --- |',
        '| spec-00001-AC-1.1 | 模型层 | DOM |',
      ].join('\n'),
    })

    expect(rows).toEqual([])
  })

  it('skips a checklist row whose first cell is not a requirement id', () => {
    const rows = acceptanceRows(
      checklist('record-00002-x', [
        ['issue-00002', 'draws an edge per relation', 'fixed'],
        ['spec-00001-AC-1.1', 'draws a node', 'pass'],
      ]),
    )

    expect(rows.map((row) => row.targetId)).toEqual(['spec-00001-AC-1.1'])
  })

  // a row that stops short of the result column has not said it passed
  it('reads a row with fewer cells than its header as an empty result', () => {
    const rows = acceptanceRows({
      id: 'record-00001-x',
      body: ['| GWT id | 测试 | 结果 |', '| --- | --- | --- |', '| spec-00001-AC-1.1 | a test |'].join('\n'),
    })

    expect(rows[0]).toMatchObject({ test: 'a test', result: '' })
  })

  // A row that stops before the evidence column has offered no evidence at all
  it('reads a row that stops before its evidence column', () => {
    const rows = acceptanceRows({
      id: 'record-00001-x',
      body: [
        '| GWT id | Test | Result | Evidence |',
        '| --- | --- | --- | --- |',
        '| spec-00001-AC-1.1 | a test | pass |',
      ].join('\n'),
    })

    expect(rows[0]).not.toHaveProperty('evidence')
    expect(rows[0]).toMatchObject({ test: 'a test', result: 'pass' })
  })

  it('reads a row that stops at its id as an empty test and an empty result', () => {
    const rows = acceptanceRows({
      id: 'record-00001-x',
      body: ['| GWT id | 测试 | 结果 |', '| --- | --- | --- |', '| spec-00001-AC-1.1 |'].join('\n'),
    })

    expect(rows[0]).toMatchObject({ targetId: 'spec-00001-AC-1.1', test: '', result: '' })
  })

  it('strips the decoration a row may carry around its cells', () => {
    const rows = acceptanceRows(checklist('record-00001-x', [['`spec-00001-AC-1.1`', '`a test`', '**pass**']]))

    expect(rows[0]).toMatchObject({ targetId: 'spec-00001-AC-1.1', test: 'a test', result: 'pass' })
  })
})

describe('coverage', () => {
  const TWO_CRITERIA = specBody(
    [item('spec-00001-FR-1')],
    [criterion('spec-00001-AC-1.1', 'spec-00001-FR-1'), criterion('spec-00001-AC-1.2', 'spec-00001-FR-1')],
  )
  const ONE_CRITERION = specBody([item('spec-00001-FR-1')], [criterion('spec-00001-AC-1.1', 'spec-00001-FR-1')])

  // spec-00001-AC-32.1
  it('calls an item verified when every criterion has a passing row', () => {
    const view = viewOf(TWO_CRITERIA, [
      checklist('record-00001-x', [
        ['spec-00001-AC-1.1', 'first test', 'pass'],
        ['spec-00001-AC-1.2', 'second test', 'pass'],
      ]),
    ])

    expect(view.items[0]!.coverage).toBe('verified')
  })

  // spec-00001-AC-32.2
  it('calls an item uncovered when no row references any of its criteria', () => {
    expect(viewOf(TWO_CRITERIA, []).items[0]!.coverage).toBe('uncovered')
  })

  // spec-00001-AC-32.3 — coverage is required criterion by criterion
  it('calls an item uncovered when only one of its two criteria has a row', () => {
    const view = viewOf(TWO_CRITERIA, [checklist('record-00001-x', [['spec-00001-AC-1.1', 'first test', 'pass']])])

    expect(view.items[0]!.coverage).toBe('uncovered')
  })

  // spec-00001-AC-32.4 — a row that did not pass outranks the rest
  it('calls an item failing when a row is n/a even though every criterion passed', () => {
    const view = viewOf(ONE_CRITERION, [
      checklist('record-00001-x', [
        ['spec-00001-AC-1.1', 'first test', 'pass'],
        ['spec-00001-AC-1.1', 'another test', 'n/a'],
      ]),
    ])

    expect(view.items[0]!.coverage).toBe('failing')
  })

  // spec-00001-AC-32.6, the data side: the state is a value of its own, not a colour
  it('carries a coverage state on every item', () => {
    const view = viewOf(ONE_CRITERION, [checklist('record-00001-x', [['spec-00001-AC-1.1', 'a test', 'pass']])])

    expect(view.items.map((found) => found.coverage)).toEqual(['verified'])
  })

  // spec-00001-AC-32.7 — the gap that most deserves to be seen
  it('calls an item with no criteria at all uncovered', () => {
    expect(viewOf(specBody([item('spec-00001-FR-1')])).items[0]!.coverage).toBe('uncovered')
  })

  // spec-00001-AC-32.8
  it('calls an item failing when a row failed and another criterion has no row', () => {
    const view = viewOf(TWO_CRITERIA, [checklist('record-00001-x', [['spec-00001-AC-1.1', 'first test', 'fail']])])

    expect(view.items[0]!.coverage).toBe('failing')
  })

  // spec-00001-AC-32.9 — an item-level row raises the alarm just as an AC row does
  it('calls an item failing when a row naming the item itself is n/a', () => {
    const view = viewOf(ONE_CRITERION, [
      checklist('record-00001-x', [
        ['spec-00001-AC-1.1', 'first test', 'pass'],
        ['spec-00001-FR-1', 'a whole-item check', 'n/a'],
      ]),
    ])

    expect(view.items[0]!.coverage).toBe('failing')
    expect(view.items[0]!.rows.map((row) => row.targetId)).toEqual(['spec-00001-FR-1'])
    expect(view.diagnostics).toEqual([])
  })

  // spec-00001-AC-32.10 — an item-level pass is not per-criterion coverage
  it('calls an item uncovered when only the item itself has a passing row', () => {
    const rows: [string, string, string][] = [['spec-00001-FR-1', 'a whole-item check', 'pass']]
    const view = viewOf(ONE_CRITERION, [checklist('record-00001-x', rows)])

    expect(view.items[0]!.coverage).toBe('uncovered')
  })

  it('takes rows from every record, whichever one carries them', () => {
    const view = viewOf(TWO_CRITERIA, [
      checklist('record-00001-x', [['spec-00001-AC-1.1', 'first test', 'pass']]),
      checklist('record-00002-x', [['spec-00001-AC-1.2', 'second test', 'pass']]),
    ])

    expect(view.items[0]!.coverage).toBe('verified')
    expect(view.items[0]!.criteria.map((found) => found.rows.map((row) => row.recordId))).toEqual([
      ['record-00001-x'],
      ['record-00002-x'],
    ])
  })

  it('ignores rows that verify another document', () => {
    const view = viewOf(ONE_CRITERION, [checklist('record-00001-x', [['rule-00001-AC-1.1', 'a test', 'fail']])])

    expect(view.items[0]!.coverage).toBe('uncovered')
    expect(view.diagnostics).toEqual([])
  })
})

describe('what cannot be attributed', () => {
  const ONE_CRITERION = specBody([item('spec-00001-FR-1')], [criterion('spec-00001-AC-1.1', 'spec-00001-FR-1')])

  // spec-00001-AC-33.1
  it('lists a row verifying a criterion that does not exist, with its record and the id it named', () => {
    const view = viewOf(ONE_CRITERION, [
      checklist('record-00001-x', [
        ['spec-00001-AC-1.1', 'a test', 'pass'],
        ['spec-00001-AC-99.1', 'a stale test', 'pass'],
      ]),
    ])

    expect(view.diagnostics).toEqual([
      {
        kind: 'unattributable',
        recordId: 'record-00001-x',
        declaredId: 'spec-00001-AC-99.1',
        line: 6,
        text: '| spec-00001-AC-99.1 | a stale test | pass |',
      },
    ])
  })

  // spec-00001-AC-33.2 — the stray row changes neither the coverage nor the count
  it('keeps the stray row out of coverage and out of the criterion count', () => {
    const view = viewOf(ONE_CRITERION, [
      checklist('record-00001-x', [
        ['spec-00001-AC-1.1', 'a test', 'pass'],
        ['spec-00001-AC-99.1', 'a stale test', 'fail'],
      ]),
    ])

    expect(view.items[0]!.coverage).toBe('verified')
    expect(view.items[0]!.criteria).toHaveLength(1)
  })

  // spec-00001-AC-33.3
  it('lists a criterion attributed to an item that does not exist, and leaves it uncounted', () => {
    const view = viewOf(
      specBody(
        [item('spec-00001-FR-1')],
        [criterion('spec-00001-AC-1.1', 'spec-00001-FR-1'), criterion('spec-00001-AC-9.1', 'spec-00001-FR-99')],
      ),
    )

    expect(view.diagnostics).toEqual([
      {
        kind: 'unattributable',
        declaredId: 'spec-00001-AC-9.1',
        attributedTo: 'spec-00001-FR-99',
        line: 11,
        text: '- **spec-00001-AC-9.1** (spec-00001-FR-99)',
      },
    ])
    expect(view.items[0]!.criteria.map((found) => found.id)).toEqual(['spec-00001-AC-1.1'])
  })

  // spec-00001-AC-40.9 — the attribution is not optional, so its absence is reported
  it('lists a criterion that names no item at all, and leaves it uncounted', () => {
    const view = viewOf(specBody([item('spec-00001-FR-1')], ['- **spec-00001-AC-1.1** Given a board']))

    expect(view.items[0]!.criteria).toEqual([])

    expect(view.diagnostics).toEqual([
      {
        kind: 'unattributable',
        declaredId: 'spec-00001-AC-1.1',
        attributedTo: undefined,
        line: 7,
        text: '- **spec-00001-AC-1.1** Given a board',
      },
    ])
  })
})

/**
 * spec-00001-FR-40: what the item grammar of the folder READMEs rejects. The
 * heuristics are deliberately narrow — a line has to open with a bold id of
 * this document's own to be suspected at all — because a false positive costs
 * more than the drift it would catch (decision-00005 §4).
 */
describe('parse diagnostics', () => {
  const ONE_CRITERION = specBody([item('spec-00001-FR-1')], [criterion('spec-00001-AC-1.1', 'spec-00001-FR-1')])

  function ruleView(body: string) {
    return requirementView({ id: 'rule-00001-docs-workflow', body }, [])
  }

  // spec-00001-AC-40.1 — the range row that cost record-00001 eight false reports
  it('reports a checklist row written as a range, and keeps it out of coverage', () => {
    const view = requirementView(
      { id: 'rule-00001-docs-workflow', body: '- **rule-00001-BR-2** (Definition) a rule\n' },
      [checklist('record-00001-x', [['rule-00001-AC-2.1 … AC-9.2', 'nine tests', 'pass']])],
    )

    expect(view.diagnostics).toEqual([
      {
        kind: 'checklist-row',
        recordId: 'record-00001-x',
        line: 5,
        text: '| rule-00001-AC-2.1 … AC-9.2 | nine tests | pass |',
      },
    ])
    expect(view.items[0]!.rows).toEqual([])
    expect(view.items[0]!.coverage).toBe('uncovered')
  })

  // spec-00001-AC-40.7
  it('reports a checklist row holding two ids in one cell, and keeps it out of coverage', () => {
    const view = viewOf(ONE_CRITERION, [
      checklist('record-00001-x', [['spec-00001-AC-1.1, spec-00001-AC-1.2', 'one test', 'pass']]),
    ])

    expect(view.diagnostics.map((found) => found.kind)).toEqual(['checklist-row'])
    expect(view.items[0]!.criteria[0]!.rows).toEqual([])
    expect(view.items[0]!.coverage).toBe('uncovered')
  })

  // spec-00001-AC-40.6 — the same rows, written out one per line
  it('drops the diagnostic and takes the rows once the range is expanded', () => {
    const view = viewOf(ONE_CRITERION, [
      checklist('record-00001-x', [['spec-00001-AC-1.1', 'the one test', 'pass']]),
    ])

    expect(view.diagnostics).toEqual([])
    expect(view.items[0]!.coverage).toBe('verified')
  })

  // The verified document is what the row was reaching for, so that is where it is reported.
  it('reports a malformed row against the document its ids belong to, not the others', () => {
    const records = [checklist('record-00001-x', [['rule-00001-AC-2.1 … AC-9.2', 'nine tests', 'pass']])]

    expect(viewOf(ONE_CRITERION, records).diagnostics).toEqual([])
    expect(ruleView('- **rule-00001-BR-2** (Definition) a rule\n').diagnostics).toHaveLength(0)
  })

  // A first cell of document ids is a different table; the amendment tables rely on it
  it('says nothing about a checklist row whose first cell names no item id', () => {
    const view = viewOf(ONE_CRITERION, [checklist('record-00001-x', [['issue-00002', 'a fix', 'fixed']])])

    expect(view.diagnostics).toEqual([])
  })

  // spec-00001-AC-40.2
  it('reports a line opening with a bold item id that is neither declaration shape', () => {
    const view = viewOf(
      ['## 4. System Requirements', '', item('spec-00001-FR-1'), '', '**spec-00001-FR-2** (Event) 掉了列表符号。'].join(
        '\n',
      ),
    )

    expect(view.diagnostics).toEqual([
      {
        kind: 'item-shape',
        declaredId: 'spec-00001-FR-2',
        line: 5,
        text: '**spec-00001-FR-2** (Event) 掉了列表符号。',
      },
    ])
    expect(view.items.map((found) => found.id)).toEqual(['spec-00001-FR-1'])
  })

  // spec-00001-AC-40.8 — a table row that never became a table
  it('reports a bold rule id left in a single table cell', () => {
    const view = ruleView(['| **rule-00001-BR-2** |', '', '- **rule-00001-BR-3** (Definition) a whole rule'].join('\n'))

    expect(view.diagnostics).toEqual([
      { kind: 'item-shape', declaredId: 'rule-00001-BR-2', line: 1, text: '| **rule-00001-BR-2** |' },
    ])
    expect(view.items.map((found) => found.id)).toEqual(['rule-00001-BR-3'])
  })

  // 「整行起头」 is the grammar's word and the heuristic's limit: an indented
  // line starts nothing, so a nested item declares nothing and is not suspected
  // either — the narrow reading, which keeps prose out of the diagnostics.
  it('neither declares nor reports a bold id nested under another list item', () => {
    const view = viewOf(['- some heading of a list', '  - **spec-00001-FR-1** (Event) 缩进了一级。'].join('\n'))

    expect(view.items).toEqual([])
    expect(view.diagnostics).toEqual([])
  })

  // Markdown lets a block hang three spaces in; 「整行起头」 does not
  it('takes an indented list or table as neither a declaration nor a diagnostic', () => {
    const indentedList = viewOf(['   - **spec-00001-FR-1** (Event) 缩进了三格。', ''].join('\n'))
    const indentedTable = ruleView(
      ['   | # | 目标 |', '   | --- | --- |', '   | **rule-00001-BR-2** | `active` |', ''].join('\n'),
    )

    expect(indentedList).toEqual({ items: [], diagnostics: [] })
    expect(indentedTable).toEqual({ items: [], diagnostics: [] })
  })

  it('takes a list item that opens with something other than a paragraph as no declaration', () => {
    const view = viewOf('- - **spec-00001-FR-1** (Event) 嵌在另一个列表项里。\n')

    expect(view).toEqual({ items: [], diagnostics: [] })
  })

  it('says nothing about either declaration shape when it is well formed', () => {
    expect(viewOf(specBody([item('spec-00001-FR-1')])).diagnostics).toEqual([])
    expect(
      ruleView(
        [
          '| # | 种类 | 目标 |',
          '| --- | --- | --- |',
          '| **rule-00001-BR-2** | living doc | `active` |',
        ].join('\n'),
      ).diagnostics,
    ).toEqual([])
  })

  // The grammar says a whole-line quotation of another document declares nothing
  it('says nothing about a well-formed line declaring another document`s id', () => {
    const view = viewOf(specBody([item('spec-00001-FR-1'), item('rule-00001-BR-1')]))

    expect(view.diagnostics).toEqual([])
  })

  it('says nothing about an id quoted in prose or inside a fenced block', () => {
    const view = viewOf(
      [
        item('spec-00001-FR-1'),
        '',
        'The `spec-00001-FR-2` id is quoted here, and **bold prose** is just bold prose.',
        '',
        '```markdown',
        '- **spec-00001-FR-3** (Event) an example of the shape',
        '**spec-00001-FR-4** (Event) and an example of what it must not be',
        '```',
      ].join('\n'),
    )

    expect(view.diagnostics).toEqual([])
  })
})

import { describe, expect, it } from 'vitest'
import type { DocGraph, DocNode } from '../../src/docRepository.ts'
import {
  COLUMN_GAP,
  NODE_HEIGHT,
  NODE_WIDTH,
  ROW_GAP,
  groupKey,
  layoutGraph,
  orderedColumns,
} from '../src/layout.ts'
import { toFlowEdges } from '../src/canvasModel.ts'

function node(overrides: Partial<DocNode> = {}): DocNode {
  return {
    id: 'prd-00001-x',
    path: 'prd/a.md',
    type: 'prd',
    status: 'draft',
    title: 'X',
    relations: {},
    ok: true,
    problems: [],
    ...overrides,
  }
}

// spec-00001-AC-1.1, AC-1.2 and AC-1.6 … AC-1.9 (decision-00002 §2)
describe('the layout', () => {
  const ORDER = ['idea', 'prd', 'spec', 'rule']

  function graphOf(...nodes: DocNode[]): DocGraph {
    return { nodes, edges: [], issues: [], diagnostics: [], idOwners: {} }
  }

  function at(placed: { id: string; x: number; y: number }[], id: string) {
    return placed.find((item) => item.id === id)!
  }

  /**
   * The layout of a whole graph. Every fixture in this block is top-level
   * documents only, so no expanded state can make a difference to it (AC-4.5).
   */
  function laid(graph: DocGraph) {
    return layoutGraph(orderedColumns(graph, ORDER), [])
  }

  // spec-00001-AC-1.6
  it('places each type in its own column, left to right', () => {
    const placed = laid(graphOf(
        node({ id: 'spec-00001-x', type: 'spec', path: 'spec/a.md' }),
        node({ id: 'idea-00001-x', type: 'idea', path: 'idea/a.md' }),
        node(),
      ))

    expect(at(placed, 'idea-00001-x').x).toBeLessThan(at(placed, 'prd-00001-x').x)
    expect(at(placed, 'prd-00001-x').x).toBeLessThan(at(placed, 'spec-00001-x').x)
    expect(new Set(placed.map((item) => item.y))).toEqual(new Set([0]))
  })

  // spec-00001-AC-1.7
  it('stacks documents of the same type in one column, by id', () => {
    const placed = laid(graphOf(
        node({ id: 'spec-00002-b', type: 'spec', path: 'spec/b.md' }),
        node({ id: 'spec-00001-a', type: 'spec', path: 'spec/a.md' }),
      ))

    expect(at(placed, 'spec-00001-a').x).toBe(at(placed, 'spec-00002-b').x)
    expect(at(placed, 'spec-00001-a').y).toBeLessThan(at(placed, 'spec-00002-b').y)
  })

  // spec-00001-AC-1.8 — `prd` is declared between them but has no document
  it('leaves no empty column for a type with no documents', () => {
    const placed = laid(graphOf(
        node({ id: 'idea-00001-x', type: 'idea', path: 'idea/a.md' }),
        node({ id: 'spec-00001-x', type: 'spec', path: 'spec/a.md' }),
      ))

    const gap = at(placed, 'spec-00001-x').x - at(placed, 'idea-00001-x').x
    expect(gap).toBe(NODE_WIDTH + COLUMN_GAP)
  })

  // spec-00001-AC-1.9
  it('puts an undeclared type after every declared one', () => {
    const placed = laid(graphOf(node({ id: 'weird-00001-x', type: 'weird', path: 'weird/a.md' }), node()))

    expect(at(placed, 'weird-00001-x').x).toBeGreaterThan(at(placed, 'prd-00001-x').x)
  })

  it('puts a document with no type last of all', () => {
    const placed = laid(graphOf(
        node({ id: 'docs/broken.md', type: undefined, path: 'docs/broken.md', ok: false }),
        node({ id: 'weird-00001-x', type: 'weird', path: 'weird/a.md' }),
        node(),
      ))

    expect(at(placed, 'docs/broken.md').x).toBeGreaterThan(at(placed, 'weird-00001-x').x)
  })

  // issue-00004 is still open: an empty `type:` must not become a column of its
  // own, sorting ahead of the genuinely named unknown types.
  it('treats an empty type as a missing one', () => {
    const placed = laid(graphOf(
        node({ id: 'empty-00001-x', type: '', path: 'empty/a.md' }),
        node({ id: 'weird-00001-x', type: 'weird', path: 'weird/a.md' }),
        node(),
      ))

    expect(at(placed, 'empty-00001-x').x).toBeGreaterThan(at(placed, 'weird-00001-x').x)
  })

  // Two documents may share an id (issue-00004); the row order stays total, so
  // the layout function itself never returns two identical coordinates.
  it('breaks an id tie with the file path', () => {
    const placed = laid(graphOf(node({ path: 'prd/b.md' }), node({ path: 'prd/a.md' })))

    expect(placed.map((item) => item.y)).toEqual([0, NODE_HEIGHT + ROW_GAP])
  })

  // spec-00001-AC-1.13 — the lone document still lands in its own type column,
  // so the fixture needs a neighbour of another type to make that observable.
  it('places a document that declares no relations, with no edge on it', () => {
    const graph = graphOf(node(), node({ id: 'idea-00001-x', type: 'idea', path: 'idea/a.md' }))
    const placed = laid(graph)

    expect(at(placed, 'prd-00001-x')).toEqual({ id: 'prd-00001-x', x: NODE_WIDTH + COLUMN_GAP, y: 0 })
    expect(toFlowEdges(graph, placed)).toEqual([])
  })

  // spec-00001-AC-1.4
  it('places nothing for an empty graph', () => {
    expect(laid({ nodes: [], edges: [], issues: [], diagnostics: [], idOwners: {} })).toEqual([])
  })

  // spec-00001-AC-2.2 — a broken edge must not drag its ghost target into the layout
  it('ignores edges pointing at an unknown document', () => {
    const placed = laid({
        nodes: [node()],
        edges: [
          {
            from: 'prd-00001-x',
            to: 'idea-09999-ghost',
            relation: 'parent',
            ok: false,
            declaredTargets: ['idea-09999-ghost'],
          },
        ],
        issues: [],
        idOwners: {},
        diagnostics: [],
      })
    expect(placed).toHaveLength(1)
  })
})

/**
 * The directory groups (spec-00010-FR-4, design-00002 §19.1). The row pitch is
 * the one above: a group node takes exactly one row, so a collapsed group is a
 * single node position and an expanded one pushes the rows under it down.
 */
describe('directory groups', () => {
  const ORDER = ['design', 'reference', 'analysis']
  const ROW = NODE_HEIGHT + ROW_GAP

  /** The expand key as `layout.ts` builds it: the column key, NUL, the group key. */
  const expandKey = (columnKey: string, key: string) => `${columnKey}\u0000${key}`
  const groupId = (columnKey: string, key: string) => `group:${expandKey(columnKey, key)}`

  function graphOf(...nodes: DocNode[]): DocGraph {
    return { nodes, edges: [], issues: [], diagnostics: [], idOwners: {} }
  }

  function reference(id: string, path: string): DocNode {
    return node({ id, path, type: 'reference' })
  }

  // spec-00010-AC-4.1
  it('orders a column as its top-level documents, then its groups by key', () => {
    const columns = orderedColumns(
      graphOf(
        reference('reference-00002-b', 'reference/b.md'),
        reference('reference-00001-a', 'reference/a.md'),
        reference('reference-00011-s', 'reference/stripe/one.md'),
        reference('reference-00012-s', 'reference/stripe/two.md'),
        reference('reference-00013-s', 'reference/stripe/three.md'),
        reference('reference-00021-c', 'reference/ccbill/one.md'),
        reference('reference-00022-c', 'reference/ccbill/two.md'),
      ),
      ORDER,
    )

    expect(columns[0]!.top.map((item) => item.id)).toEqual(['reference-00001-a', 'reference-00002-b'])
    expect(columns[0]!.groups.map((group) => [group.key, group.nodes.length])).toEqual([
      ['reference/ccbill', 2],
      ['reference/stripe', 3],
    ])
    expect(layoutGraph(columns, []).map((item) => item.id)).toEqual([
      'reference-00001-a',
      'reference-00002-b',
      groupId('reference', 'reference/ccbill'),
      groupId('reference', 'reference/stripe'),
    ])
  })

  // spec-00010-AC-4.2
  it('folds a deeper document into its first-level subdirectory', () => {
    const deep = reference('reference-00011-s', 'reference/stripe/source/deep/x.md')
    const columns = orderedColumns(graphOf(deep), ORDER)

    expect(groupKey(deep)).toBe('reference/stripe')
    expect(columns[0]!.groups.map((group) => group.key)).toEqual(['reference/stripe'])
    expect(columns[0]!.groups[0]!.nodes).toEqual([deep])
  })

  // spec-00010-AC-4.3, AC-5.13 — one directory, two types: a group per
  // column, each with its own expand key, and the name drops the first segment
  // only where it repeats the column's own type.
  it('splits one directory across the columns of its types', () => {
    const columns = orderedColumns(
      graphOf(
        reference('reference-00011-s', 'reference/stripe/one.md'),
        reference('reference-00012-s', 'reference/stripe/two.md'),
        node({ id: 'analysis-00001-s', path: 'reference/stripe/three.md', type: 'analysis' }),
      ),
      ORDER,
    )

    expect(columns.map((column) => column.key)).toEqual(['reference', 'analysis'])
    expect(columns.map((column) => column.groups.map((group) => [group.name, group.nodes.length]))).toEqual([
      [['stripe', 2]],
      [['reference/stripe', 1]],
    ])
    expect(columns[0]!.groups[0]!.expandKey).toBe(expandKey('reference', 'reference/stripe'))
    expect(columns[1]!.groups[0]!.expandKey).toBe(expandKey('analysis', 'reference/stripe'))
    expect(columns[1]!.groups[0]!.columnKey).toBe('analysis')
  })

  // spec-00010-AC-4.4 — the documents without a declared type group by directory
  // in their own column; `untyped` is that column's display name, not the key
  // its groups are remembered by.
  it('groups the documents of the column without a declared type', () => {
    const untyped = (path: string) => node({ id: path, path, type: undefined, ok: false })
    const columns = orderedColumns(
      graphOf(
        untyped('reference/stripe/source/a.md'),
        untyped('reference/stripe/source/b.md'),
        untyped('reference/stripe/source/c.md'),
      ),
      ORDER,
    )

    expect(columns[0]!.type).toBe('untyped')
    expect(columns[0]!.groups.map((group) => [group.name, group.nodes.length])).toEqual([['reference/stripe', 3]])
    expect(columns[0]!.groups[0]!.expandKey).toBe(expandKey('', 'reference/stripe'))
    expect(layoutGraph(columns, []).map((item) => item.id)).toEqual([groupId('', 'reference/stripe')])
  })

  // spec-00010-AC-4.5
  it('leaves a column of top-level documents exactly as it was', () => {
    const columns = orderedColumns(
      graphOf(reference('reference-00001-a', 'reference/a.md'), reference('reference-00002-b', 'reference/b.md')),
      ORDER,
    )

    expect(columns[0]!.groups).toEqual([])
    expect(layoutGraph(columns, [])).toEqual([
      { id: 'reference-00001-a', x: 0, y: 0 },
      { id: 'reference-00002-b', x: 0, y: ROW },
    ])
  })

  // spec-00010-AC-4.6 — a path of one segment has no subdirectory to group by.
  it('keeps a document sitting directly under docs/ at the top of its column', () => {
    const columns = orderedColumns(
      graphOf(reference('reference-00001-a', 'x.md'), reference('reference-00011-s', 'reference/stripe/one.md')),
      ORDER,
    )

    expect(columns[0]!.top.map((item) => item.id)).toEqual(['reference-00001-a'])
    expect(layoutGraph(columns, []).map((item) => item.id)).toEqual([
      'reference-00001-a',
      groupId('reference', 'reference/stripe'),
    ])
  })

  // spec-00010-AC-4.7 — no threshold: one document is a group.
  it('makes a group of a subdirectory holding a single document', () => {
    const columns = orderedColumns(graphOf(reference('reference-00031-m', 'reference/manifest/one.md')), ORDER)

    expect(columns[0]!.groups.map((group) => [group.key, group.nodes.length])).toEqual([['reference/manifest', 1]])
  })

  // spec-00010-AC-4.8 — a group is made of nodes, so a directory whose files all
  // yield none never becomes one. Which files yield none is settled server-side
  // and tested there: a `README.md` is never a document (AC-1.4) and a file an
  // `exclude` pattern matches does not exist for the board (AC-1.1). What is
  // left for the layout is that such a directory reaches it as an absent path.
  it('makes no group of a directory whose files all yield no node', () => {
    // The tree on disk: `reference/empty/` holds only a `README.md` and every
    // file under `reference/mirror/` is hit by `exclude`, so `readGraph` hands
    // over one node of the three files and the layout sees these two paths not
    // at all.
    const nodeless = ['reference/empty/README.md', 'reference/mirror/x.md']
    const columns = orderedColumns(graphOf(reference('reference-00011-s', 'reference/stripe/one.md')), ORDER)

    const keys = columns[0]!.groups.map((group) => group.key)
    expect(keys).toEqual(['reference/stripe'])
    for (const path of nodeless) expect(keys).not.toContain(groupKey(node({ path })))
  })

  // spec-00010-AC-5.14 — two directories of the same name in one column: the one
  // whose folder repeats the column's type drops that segment, the other keeps
  // both. Group rows are group-key order, so `reference/stripe` comes first.
  it('names two groups of one column by what their folders share with it', () => {
    const columns = orderedColumns(
      graphOf(
        reference('reference-00011-s', 'reference/stripe/one.md'),
        reference('reference-00012-s', 'reference/stripe/two.md'),
        reference('reference-00013-x', 'spec/stripe/x.md'),
      ),
      ORDER,
    )

    expect(columns.map((column) => column.key)).toEqual(['reference'])
    expect(columns[0]!.groups.map((group) => [group.key, group.name])).toEqual([
      ['reference/stripe', 'stripe'],
      ['spec/stripe', 'spec/stripe'],
    ])
  })

  // spec-00010-AC-6.1 — expanding a group lays its members out under its own row
  // and pushes everything below down by exactly that many rows.
  it('lays an expanded group out under its group node', () => {
    const columns = orderedColumns(
      graphOf(
        reference('reference-00011-s', 'reference/stripe/one.md'),
        reference('reference-00012-s', 'reference/stripe/two.md'),
        reference('reference-00021-c', 'reference/ccbill/one.md'),
      ),
      ORDER,
    )
    const open = expandKey('reference', 'reference/ccbill')

    expect(layoutGraph(columns, [open]).map((item) => [item.id, item.y])).toEqual([
      [groupId('reference', 'reference/ccbill'), 0],
      ['reference-00021-c', ROW],
      [groupId('reference', 'reference/stripe'), 2 * ROW],
    ])
    expect(layoutGraph(columns, new Set([open])).map((item) => item.y)).toEqual([0, ROW, 2 * ROW])
  })

  // The columns stay side by side: opening a group moves no other column.
  it('keeps the columns where they were when a group opens', () => {
    const columns = orderedColumns(
      graphOf(
        reference('reference-00011-s', 'reference/stripe/one.md'),
        node({ id: 'design-00001-a', path: 'design/a.md', type: 'design' }),
      ),
      ORDER,
    )
    const placed = layoutGraph(columns, [expandKey('reference', 'reference/stripe')])

    expect(placed.find((item) => item.id === 'design-00001-a')).toEqual({ id: 'design-00001-a', x: 0, y: 0 })
    expect(placed.filter((item) => item.x === NODE_WIDTH + COLUMN_GAP)).toHaveLength(2)
  })
})

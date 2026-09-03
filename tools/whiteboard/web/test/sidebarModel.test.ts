import { describe, expect, it } from 'vitest'
import type { DocGraph, DocNode } from '../../src/docRepository.ts'
import { orderedColumns } from '../src/layout.ts'
import { typeGroups } from '../src/sidebarModel.ts'

const ORDER = ['design', 'reference']

function node(id: string, path: string, type?: string): DocNode {
  return { id, path, type, status: 'active', title: id, relations: {}, ok: true, problems: [] }
}

function graphOf(...nodes: DocNode[]): DocGraph {
  return { nodes, edges: [], issues: [], diagnostics: [], idOwners: {} }
}

describe('typeGroups', () => {
  // spec-00010-AC-8.1 — the sidebar mirrors the column: the top-level documents,
  // then the directory groups, and the header counts every document of the type.
  it('mirrors a column as its top documents, its directories and the whole list', () => {
    const top = node('reference-00001-a', 'reference/a.md', 'reference')
    const inside = node('reference-00011-s', 'reference/stripe/one.md', 'reference')

    const [group] = typeGroups(orderedColumns(graphOf(inside, top), ORDER))

    expect(group).toMatchObject({ key: 'reference', type: 'reference' })
    expect(group!.top).toEqual([top])
    expect(group!.directories.map((directory) => [directory.name, directory.nodes])).toEqual([['stripe', [inside]]])
    expect(group!.nodes).toEqual([top, inside])
  })

  it('names the group of the documents without a declared type', () => {
    const [group] = typeGroups(orderedColumns(graphOf(node('broken.md', 'broken.md')), ORDER))

    expect(group).toMatchObject({ key: '', type: 'untyped', directories: [] })
  })
})

import { describe, expect, it } from 'vitest'
import { type Expectation, findProduct, markProduct, productProblems, taskInstruction } from '../src/advance.ts'
import { readGraph } from '../src/docRepository.ts'
import { doc, makeDocsDir, testConfig } from './helpers.ts'

const config = testConfig()
const EXPECTATION: Expectation = {
  targetType: 'prd',
  idPrefix: 'prd-00002-',
  carry: 'parent',
  sourceId: 'idea-00001-x',
}

function graphOf(files: Record<string, string>) {
  return readGraph(makeDocsDir(files), config)
}

// spec-00001-AC-11.2
describe('taskInstruction', () => {
  it('names the target type, the fixed id number, and the relation to the source', () => {
    const instruction = taskInstruction(EXPECTATION)

    expect(instruction).toContain('Write one new prd document')
    expect(instruction).toContain('prd-00002-<slug>')
    expect(instruction).toContain('parent: idea-00001-x')
  })

  it('points at the folder template and readme and pins the status', () => {
    const instruction = taskInstruction(EXPECTATION)

    expect(instruction).toContain('prd/TEMPLATE.md')
    expect(instruction).toContain('prd/README.md')
    expect(instruction).toContain('status: draft')
  })

  /** spec-00001-FR-41: the item grammar travels with the brief, or not at all. */
  function instructionFor(targetType: string): string {
    return taskInstruction({ ...EXPECTATION, targetType, idPrefix: `${targetType}-00002-` })
  }

  // spec-00001-AC-41.1
  it('carries the item grammar of a spec: both declaration shapes and the AC attribution', () => {
    const instruction = instructionFor('spec')

    expect(instruction).toContain('spec/README.md')
    expect(instruction).toContain('- **spec-<n>-FR-<i>**')
    expect(instruction).toContain('| **spec-<n>-FR-<i>**')
    expect(instruction).toContain('- **spec-<n>-AC-<i>.<k>** (spec-<n>-FR-<i>)')
    expect(instruction).toMatch(/attribution in\s+parentheses is required/)
  })

  it('carries the same two shapes for a rule, in the rule`s own ids', () => {
    const instruction = instructionFor('rule')

    expect(instruction).toContain('- **rule-<n>-BR-<i>**')
    expect(instruction).toContain('| **rule-<n>-BR-<i>**')
    expect(instruction).toContain('- **rule-<n>-AC-<i>.<k>** (rule-<n>-BR-<i>)')
  })

  it('carries the checklist grammar for a record: one id a row, no ranges', () => {
    const instruction = instructionFor('record')

    expect(instruction).toContain('exactly one requirement or AC id')
    expect(instruction).toContain('No ranges')
  })

  // spec-00001-AC-41.2 — idea has no item grammar, so the section is absent
  it('says nothing about an item grammar for a type that has none', () => {
    for (const targetType of ['idea', 'prd', 'design', 'plan']) {
      expect(instructionFor(targetType)).not.toContain('机器可读形态')
      expect(instructionFor(targetType)).not.toContain('item grammar')
    }
  })
})

describe('findProduct', () => {
  it('finds the document carrying the allocated prefix', () => {
    const graph = graphOf({
      'prd/a.md': doc({ id: 'prd-00001-old', type: 'prd', status: 'active' }),
      'prd/b.md': doc({ id: 'prd-00002-new', type: 'prd', status: 'draft' }),
    })
    expect(findProduct(graph, 'prd-00002-')!.id).toBe('prd-00002-new')
  })

  it('finds nothing when the session produced no document', () => {
    expect(findProduct(graphOf({}), 'prd-00002-')).toBeUndefined()
  })
})

describe('productProblems', () => {
  // spec-00001-AC-17.2
  it('finds nothing wrong with a compliant document', () => {
    const graph = graphOf({
      'idea/a.md': doc({ id: 'idea-00001-x', type: 'idea', status: 'active' }),
      'prd/b.md': doc({ id: 'prd-00002-new', type: 'prd', status: 'draft', parent: 'idea-00001-x' }),
    })
    expect(productProblems(findProduct(graph, 'prd-00002-')!, EXPECTATION)).toEqual([])
  })

  // spec-00001-AC-17.1
  it('reports a missing relation to the source document', () => {
    const graph = graphOf({ 'prd/b.md': doc({ id: 'prd-00002-new', type: 'prd', status: 'draft' }) })
    expect(productProblems(findProduct(graph, 'prd-00002-')!, EXPECTATION)).toEqual([
      'parent does not point at idea-00001-x',
    ])
  })

  it('reports a relation pointing at the wrong document', () => {
    const graph = graphOf({
      'prd/b.md': doc({ id: 'prd-00002-new', type: 'prd', status: 'draft', parent: 'idea-00009-other' }),
    })
    expect(productProblems(findProduct(graph, 'prd-00002-')!, EXPECTATION)).toContain(
      'parent does not point at idea-00001-x',
    )
  })

  it('reports a document of the wrong type and keeps its own front matter problems', () => {
    const graph = graphOf({ 'prd/b.md': doc({ id: 'prd-00002-new', type: 'prd', status: 'nonsense' }) })
    const problems = productProblems(findProduct(graph, 'prd-00002-')!, { ...EXPECTATION, targetType: 'spec' })

    expect(problems).toContain('status "nonsense" is not a status of a living document')
    expect(problems).toContain('type "prd" is not the requested spec')
  })
})

describe('markProduct', () => {
  it('marks the produced node anomalous and lists its problems as issues', () => {
    const graph = graphOf({ 'prd/b.md': doc({ id: 'prd-00002-new', type: 'prd', status: 'draft' }) })

    const marked = markProduct(graph, 'prd-00002-new', ['parent does not point at idea-00001-x'])

    expect(marked.nodes[0]!.ok).toBe(false)
    expect(marked.nodes[0]!.problems).toContain('parent does not point at idea-00001-x')
    expect(marked.issues).toEqual([{ path: 'prd/b.md', nodeId: 'prd-00002-new', message: 'parent does not point at idea-00001-x' }])
  })

  it('marks only the produced node, leaving its neighbours untouched', () => {
    const graph = graphOf({
      'idea/a.md': doc({ id: 'idea-00001-x', type: 'idea', status: 'active' }),
      'prd/b.md': doc({ id: 'prd-00002-new', type: 'prd', status: 'draft' }),
    })

    const marked = markProduct(graph, 'prd-00002-new', ['parent does not point at idea-00001-x'])

    expect(marked.nodes.find((node) => node.id === 'idea-00001-x')!.ok).toBe(true)
    expect(marked.nodes.find((node) => node.id === 'prd-00002-new')!.ok).toBe(false)
  })

  it('leaves the graph alone when there is nothing to report', () => {
    const graph = graphOf({ 'prd/b.md': doc({ id: 'prd-00002-new', type: 'prd', status: 'draft' }) })
    expect(markProduct(graph, 'prd-00002-new', [])).toBe(graph)
  })

  it('falls back to the document id when the node is not in the graph', () => {
    const marked = markProduct(graphOf({}), 'prd-00002-ghost', ['it vanished'])
    expect(marked.issues).toEqual([{ path: 'prd-00002-ghost', nodeId: 'prd-00002-ghost', message: 'it vanished' }])
  })
})

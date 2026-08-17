import { existsSync, mkdirSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { readGraph } from '../src/docRepository.ts'
import {
  askInstruction,
  clarifyInstruction,
  clarifyStatePath,
  readClarifyState,
  relatedDocPaths,
  removeClarifyState,
} from '../src/sessionTasks.ts'
import { doc, makeDocsDir, makeRepo, testConfig } from './helpers.ts'

const config = testConfig()

const TASK = {
  docPath: 'spec/board.md',
  relatedPaths: ['idea/board.md', 'rule/flow.md'],
  focus: 'FR boundaries and acceptance gaps',
  statePath: '.whiteboard/clarify/spec-00001-board.json',
}

describe('clarifyInstruction', () => {
  it('names the session kind, the document, its context, the focus line, and the state file', () => {
    const instruction = clarifyInstruction(TASK)

    expect(instruction).toContain('clarify session')
    expect(instruction).toContain('spec/board.md')
    expect(instruction).toContain('idea/board.md, rule/flow.md')
    expect(instruction).toContain('FR boundaries and acceptance gaps')
    expect(instruction).toContain('.whiteboard/clarify/spec-00001-board.json')
  })

  it('leaves the relation context out when the document has none', () => {
    expect(clarifyInstruction({ ...TASK, relatedPaths: [] })).not.toContain('relation documents')
  })

  it('carries the progress to recover from only when there is some', () => {
    expect(clarifyInstruction({ ...TASK, state: '{"asked":["who owns pricing?"]}' })).toContain('who owns pricing?')
    expect(clarifyInstruction(TASK)).not.toContain('Already answered')
  })
})

describe('askInstruction', () => {
  it('names the session kind, the document and its context, and rules out status changes', () => {
    const instruction = askInstruction({ docPath: 'record/r.md', relatedPaths: ['spec/board.md'] })

    expect(instruction).toContain('ask session')
    expect(instruction).toContain('record/r.md')
    expect(instruction).toContain('spec/board.md')
    expect(instruction).toContain('never touch a status line')
  })

  it('leaves the relation context out when the document has none', () => {
    expect(askInstruction({ docPath: 'record/r.md', relatedPaths: [] })).not.toContain('relation documents')
  })
})

/** spec-00001-FR-46: the file is the session's progress, and the board only reads and drops it. */
describe('the clarify state file', () => {
  it('sits under .whiteboard, keyed by document id', () => {
    expect(clarifyStatePath('spec-00001-board')).toBe('.whiteboard/clarify/spec-00001-board.json')
  })

  function stateOn(content?: string) {
    const { repoRoot } = makeRepo({})
    if (content !== undefined) {
      mkdirSync(join(repoRoot, '.whiteboard/clarify'), { recursive: true })
      writeFileSync(join(repoRoot, clarifyStatePath('prd-00001-x')), content)
    }
    return repoRoot
  }

  it('reads the progress a previous session left', () => {
    expect(readClarifyState(stateOn('{"answered":2}'), 'prd-00001-x')).toBe('{"answered":2}')
  })

  it('reads nothing when there is no file', () => {
    expect(readClarifyState(stateOn(), 'prd-00001-x')).toBeUndefined()
  })

  it('reads a file that is not valid JSON as nothing', () => {
    expect(readClarifyState(stateOn('{ half written'), 'prd-00001-x')).toBeUndefined()
  })

  it('removes the file, and minds no absent one', () => {
    const repoRoot = stateOn('{"answered":2}')

    removeClarifyState(repoRoot, 'prd-00001-x')

    expect(existsSync(join(repoRoot, clarifyStatePath('prd-00001-x')))).toBe(false)
    expect(() => removeClarifyState(repoRoot, 'prd-00001-x')).not.toThrow()
  })
})

/** spec-00001-FR-45 and FR-47: the context is the relation documents, both directions. */
describe('relatedDocPaths', () => {
  const graphOf = (files: Record<string, string>) => readGraph(makeDocsDir(files), config)

  it('takes both the documents it points at and the ones pointing at it', () => {
    const graph = graphOf({
      'spec/board.md': doc({ id: 'spec-00001-b', type: 'spec', status: 'draft', parent: 'prd-00001-b' }),
      'prd/board.md': doc({ id: 'prd-00001-b', type: 'prd', status: 'active' }),
      'plan/mvp.md': doc({ id: 'plan-00001-m', type: 'plan', status: 'open', implements: '[spec-00001-b]' }),
    })

    expect(relatedDocPaths(graph, 'spec-00001-b')).toEqual(['plan/mvp.md', 'prd/board.md'])
  })

  it('is empty for a document with no relations either way', () => {
    const graph = graphOf({ 'idea/a.md': doc({ id: 'idea-00001-a', type: 'idea', status: 'draft' }) })
    expect(relatedDocPaths(graph, 'idea-00001-a')).toEqual([])
  })

  it('leaves out a relation that resolves to nothing', () => {
    const graph = graphOf({
      'spec/board.md': doc({ id: 'spec-00001-b', type: 'spec', status: 'draft', parent: 'prd-09999-ghost' }),
    })
    expect(relatedDocPaths(graph, 'spec-00001-b')).toEqual([])
  })

  it('does not hand a document its own path when it refers to itself', () => {
    const graph = graphOf({
      'spec/board.md': doc({ id: 'spec-00001-b', type: 'spec', status: 'draft', supersedes: '[spec-00001-b]' }),
    })
    expect(relatedDocPaths(graph, 'spec-00001-b')).toEqual([])
  })

  it('takes one path per relation document, however many relations they share', () => {
    const graph = graphOf({
      'spec/board.md': doc({ id: 'spec-00001-b', type: 'spec', status: 'draft', parent: 'prd-00001-b' }),
      'prd/board.md': doc({ id: 'prd-00001-b', type: 'prd', status: 'active', informs: '[spec-00001-b]' }),
    })

    expect(relatedDocPaths(graph, 'spec-00001-b')).toEqual(['prd/board.md'])
  })
})

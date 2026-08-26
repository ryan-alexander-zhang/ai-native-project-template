import { existsSync, mkdirSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { readGraph } from '../src/docRepository.ts'
import {
  askInstruction,
  auditInstruction,
  clarifyInstruction,
  clarifyStatePath,
  readClarifyState,
  relatedDocPaths,
  removeClarifyState,
  typeReadmePath,
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

  // spec-00001-AC-45.1
  it('carries the target path and both its relation document paths, as paths only', () => {
    const graph = readGraph(
      makeDocsDir({
        'spec/board.md': doc({ id: 'spec-00001-b', type: 'spec', status: 'draft', parent: 'prd-00001-b' }),
        'prd/board.md': doc({ id: 'prd-00001-b', type: 'prd', status: 'active' }),
        'plan/mvp.md': doc({ id: 'plan-00001-m', type: 'plan', status: 'open', implements: '[spec-00001-b]' }),
      }),
      config,
    )

    const instruction = clarifyInstruction({ ...TASK, relatedPaths: relatedDocPaths(graph, 'spec-00001-b') })

    expect(instruction).toContain('spec/board.md')
    expect(instruction).toContain('prd/board.md')
    expect(instruction).toContain('plan/mvp.md')
    expect(instruction).toContain('These are paths, not content')
  })

  // spec-00001-AC-45.2
  it('leaves the relation context out when the document has none', () => {
    const instruction = clarifyInstruction({ ...TASK, relatedPaths: [] })

    expect(instruction).toContain('spec/board.md')
    expect(instruction).not.toContain('relation documents')
  })

  // spec-00001-AC-45.3
  it('states the questioning skeleton: one at a time, at most 4 options, the recommended one first', () => {
    const instruction = clarifyInstruction(TASK)

    expect(instruction).toContain('Ask one question per turn')
    expect(instruction).toContain('at most 4 ready-made options')
    expect(instruction).toContain('the one you recommend first and marked "Recommended"')
    expect(instruction).toContain('free-form answer')
  })

  // spec-00001-AC-45.4
  it('asks the session to answer for itself whatever the documents or the repository settle', () => {
    expect(clarifyInstruction(TASK)).toContain(
      'Whatever you can answer from the documents or the repository, answer yourself',
    )
  })

  // spec-00001-AC-45.7
  it('asks the session to settle the stage advance decision with the fewest questions', () => {
    expect(clarifyInstruction(TASK)).toContain(
      "Settle this stage's advance decision with the fewest questions that do it.",
    )
  })

  // spec-00001-AC-45.6
  it('asks the session to declare the clarification saturated and close instead of asking on', () => {
    const instruction = clarifyInstruction(TASK)

    expect(instruction).toContain('Once the remaining questions would no longer change that decision')
    expect(instruction).toContain('the stop condition the focus')
    expect(instruction).toContain('declare the clarification saturated')
    expect(instruction).toContain('move to the closing instead of')
  })

  // spec-00001-AC-45.5
  it('states the closing: Open Questions, status stays draft, settled answers revise the body', () => {
    const instruction = clarifyInstruction(TASK)

    expect(instruction).toContain('append every open point the answers confirmed to the Open Questions section')
    expect(instruction).toMatch(/find the heading by name, case-insensitively and allowing a numbered form/)
    expect(instruction).toContain('create the section at the end of the file')
    expect(instruction).toContain('never create a second one')
    expect(instruction).toContain('Keep status: draft')
    expect(instruction).toContain('revise the body itself')
  })

  // spec-00001-AC-46.1
  it('points at the state file from the session`s own working directory, and says when to write it', () => {
    const instruction = clarifyInstruction({ ...TASK, statePath: clarifyStatePath('spec-00002-x') })

    expect(instruction).toContain('../.whiteboard/clarify/spec-00002-x.json')
    expect(instruction).toContain('Write it as soon as a question is answered')
    expect(instruction).toContain('delete it once every conclusion is on disk')
  })
})

describe('askInstruction', () => {
  it('names the session kind and the document with its context', () => {
    const instruction = askInstruction({ docPath: 'record/r.md', relatedPaths: ['spec/board.md'] })

    expect(instruction).toContain('ask session')
    expect(instruction).toContain('record/r.md')
    expect(instruction).toContain('spec/board.md')
  })

  it('leaves the relation context out when the document has none', () => {
    expect(askInstruction({ docPath: 'record/r.md', relatedPaths: [] })).not.toContain('relation documents')
  })

  // spec-00005-AC-1.2 — the context lines the terminal form carried, kept whole:
  // the document and every one of its relation documents, as paths
  it('carries the target path and both its relation document paths', () => {
    const graph = readGraph(
      makeDocsDir({
        'record/r.md': doc({ id: 'record-00001-r', type: 'record', status: 'active', verifies: '[spec-00001-b]' }),
        'spec/board.md': doc({ id: 'spec-00001-b', type: 'spec', status: 'draft', parent: 'prd-00001-b' }),
        'prd/board.md': doc({ id: 'prd-00001-b', type: 'prd', status: 'active', informs: '[record-00001-r]' }),
      }),
      config,
    )

    const relatedPaths = relatedDocPaths(graph, 'record-00001-r')
    const instruction = askInstruction({ docPath: 'record/r.md', relatedPaths })

    expect(instruction).toContain('record/r.md')
    expect(instruction).toContain('spec/board.md')
    expect(instruction).toContain('prd/board.md')
  })

  /**
   * spec-00005-FR-1: an ask reads and answers. The revising half the terminal
   * form carried is gone with that form — a revision is now the editor's, an
   * advance's or a clarify's (decision-00012 §5), and the constraint is stated
   * to the agent rather than left to the flags alone.
   */
  it('states that the session answers and modifies nothing at all', () => {
    const instruction = askInstruction({ docPath: 'record/r.md', relatedPaths: [] })

    expect(instruction).toContain('Modify no file')
    expect(instruction).not.toContain('Revise documents')
  })
})

/** spec-00001-FR-50 with rule-00001-BR-22: the audit's stance, its two passes, and where findings land. */
describe('auditInstruction', () => {
  const AUDIT = { docPath: 'design/board.md', readmePath: typeReadmePath('design') }

  // spec-00001-AC-50.2 — the target document and its folder README
  it('names the session kind, the document, and the folder README it is held to', () => {
    const instruction = auditInstruction(AUDIT)

    expect(instruction).toContain('audit session')
    expect(instruction).toContain('design/board.md')
    expect(instruction).toContain('design/README.md')
    expect(instruction).toContain('the README of its own folder')
  })

  // rule-00001-AC-22.1 at the instruction level: the stance and the two passes
  it('states the reviewer stance and the order of the two passes', () => {
    const instruction = auditInstruction(AUDIT)

    expect(instruction).toContain('somebody who did not write it')
    expect(instruction).toContain('never defend the existing')
    expect(instruction).toMatch(/first the structure and the grammar design\/README\.md lays down/)
    expect(instruction).toContain('then the content')
  })

  // rule-00001-BR-22: what the audit is asked to list
  it('asks for the missing rules, cases and GWTs, the silent readings, and the unconfirmable values', () => {
    const instruction = auditInstruction(AUDIT)

    expect(instruction).toContain('every rule, case and GWT that is missing')
    expect(instruction).toContain('every reading the document took')
    expect(instruction).toContain('every value you cannot confirm')
  })

  // spec-00001-AC-50.2 — the landing contract, and the status line left alone
  it('states where findings land, that duplicates are not re-appended, and that status never moves', () => {
    const instruction = auditInstruction(AUDIT)

    expect(instruction).toContain('Append each unresolved finding as a list item to the Open Questions section')
    expect(instruction).toMatch(/find the heading by name, case-insensitively and allowing a numbered form/)
    expect(instruction).toContain('create the section at the end of the file')
    expect(instruction).toContain('never create a second one')
    expect(instruction).toContain('Read what that section already holds before you write')
    expect(instruction).toContain('is not\n  appended again')
    expect(instruction).toContain('amend the body itself')
    expect(instruction).toContain('Never touch the status line')
    expect(instruction).toContain('Change nothing outside the docs tree')
  })

  // spec-00001-FR-50: audit is stateless — no progress file, nothing to recover from
  it('says nothing about a progress file or recovering from one', () => {
    const instruction = auditInstruction(AUDIT)

    expect(instruction).not.toContain('.whiteboard')
    expect(instruction).not.toContain('Recover from the progress')
  })

  it('takes the README of whichever folder the document lives in', () => {
    expect(auditInstruction({ docPath: 'rule/flow.md', readmePath: typeReadmePath('rule') })).toContain(
      'rule/README.md',
    )
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

  /** What the file on disk does to the instruction of the next clarify session. */
  function instructionRecovering(content?: string): string {
    return clarifyInstruction({ ...TASK, state: readClarifyState(stateOn(content), 'prd-00001-x') })
  }

  // spec-00001-AC-46.2
  it('carries what was already answered, and asks for it not to be asked again', () => {
    const answered = '{"answered":[{"q":"who owns pricing?","a":"the PM"},{"q":"which tier first?","a":"free"}]}'

    const instruction = instructionRecovering(answered)

    expect(instruction).toContain('who owns pricing?')
    expect(instruction).toContain('which tier first?')
    expect(instruction).toContain('ask none of them again')
  })

  // spec-00001-AC-46.3
  it('says nothing about recovering when no file was left behind', () => {
    expect(instructionRecovering()).not.toContain('Recover from the progress')
  })

  // spec-00001-AC-46.5
  it('says nothing about recovering from a file that is not valid JSON', () => {
    const instruction = instructionRecovering('{"answered":[{"q":"who owns pricing?"')

    expect(instruction).not.toContain('Recover from the progress')
    expect(instruction).not.toContain('who owns pricing?')
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

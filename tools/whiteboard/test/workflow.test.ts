import { describe, expect, it } from 'vitest'
import { readGraph } from '../src/docRepository.ts'
import {
  WorkflowError,
  allocateNumber,
  applyAccept,
  applyStatusChange,
  assertAskable,
  assertAuditable,
  assertClarifiable,
  cowriteRevision,
  hasOpenQuestions,
  issueEligible,
  idPrefix,
  nextStepsFor,
  transitionsFor,
} from '../src/workflow.ts'
import { doc, makeDocsDir, testConfig } from './helpers.ts'

const config = testConfig()

/** Build a one-document graph and hand back its node with the file content. */
function single(front: Record<string, string>, body = '') {
  const content = doc(front, body)
  const graph = readGraph(makeDocsDir({ 'x/a.md': content }), config)
  return { node: graph.nodes[0]!, content }
}

const draftPrd = () => single({ id: 'prd-00001-x', type: 'prd', status: 'draft' }, '# X\n')
const draftPlan = () => single({ id: 'plan-00001-x', type: 'plan', status: 'draft' }, '# X\n')

describe('transitionsFor', () => {
  // spec-00001-AC-6.1
  it('offers active but not open or resolved for a draft living doc', () => {
    expect(transitionsFor(draftPrd().node, config)).toEqual(['active', 'archived'])
  })

  // spec-00001-AC-6.2
  it('offers open but not active for a draft work item', () => {
    const transitions = transitionsFor(draftPlan().node, config)
    expect(transitions).toContain('open')
    expect(transitions).not.toContain('active')
  })

  // spec-00001-AC-6.3, and AC-6.5 for the revision round: `draft` is a candidate
  // of its own now (rule-00001-BR-3 as amended in the eleventh round)
  it('offers draft and archived but not resolved or open for an active living doc', () => {
    const { node } = single({ id: 'prd-00001-x', type: 'prd', status: 'active' })
    expect(transitionsFor(node, config)).toEqual(['draft', 'archived'])
  })

  // spec-00001-AC-6.4
  it('offers resolved and wontfix but not active for an open work item', () => {
    const { node } = single({ id: 'plan-00001-x', type: 'plan', status: 'open' })
    const transitions = transitionsFor(node, config)
    expect(transitions).toEqual(expect.arrayContaining(['resolved', 'wontfix']))
    expect(transitions).not.toContain('active')
  })

  // spec-00001-AC-2.4 — an anomalous document takes no workflow action
  it('offers nothing for an anomalous document', () => {
    const { node } = single({ id: 'nope', type: 'prd', status: 'draft' })
    expect(transitionsFor(node, config)).toEqual([])
  })
})

describe('applyStatusChange', () => {
  it('rewrites only the status line', () => {
    const { node, content } = draftPrd()
    const updated = applyStatusChange(content, node, config, 'active')
    expect(updated).toBe(content.replace('status: draft', 'status: active'))
  })

  // spec-00001-AC-7.1
  it('rejects a transition the table does not allow', () => {
    const { node, content } = draftPlan()
    expect(() => applyStatusChange(content, node, config, 'resolved')).toThrowError(WorkflowError)
    expect(() => applyStatusChange(content, node, config, 'resolved')).toThrowError(/not a legal transition/)
  })

  it('rejects any action on an anomalous document', () => {
    const { node, content } = single({ id: 'nope', type: 'prd', status: 'draft' })
    expect(() => applyStatusChange(content, node, config, 'active')).toThrowError(/front matter problems/)
  })

  it('rejects a document whose front matter carries no status line', () => {
    const graph = readGraph(makeDocsDir({ 'x/a.md': doc({ id: 'prd-00001-x', type: 'prd', status: 'draft' }) }), config)
    const node = graph.nodes[0]!
    expect(() => applyStatusChange('---\nid: prd-00001-x\n---\n', node, config, 'active')).toThrowError(
      /no status line/,
    )
  })
})

describe('applyAccept', () => {
  // spec-00001-AC-8.1
  it('promotes a draft living doc to active', () => {
    const { node, content } = draftPrd()
    expect(applyAccept(content, node, config)).toEqual({
      content: content.replace('status: draft', 'status: active'),
      to: 'active',
    })
  })

  // spec-00001-AC-8.2
  it('promotes a draft work item to open', () => {
    const { node, content } = draftPlan()
    expect(applyAccept(content, node, config).to).toBe('open')
  })

  // spec-00001-AC-8.3
  it('rejects a document that is already active', () => {
    const { node, content } = single({ id: 'prd-00001-x', type: 'prd', status: 'active' })
    expect(() => applyAccept(content, node, config)).toThrowError(/applies to a draft document/)
  })

  // spec-00001-AC-8.4 with rule-00001-AC-12.2
  it('rejects a draft carrying unresolved open questions', () => {
    const { node, content } = single({ id: 'prd-00001-x', type: 'prd', status: 'draft' }, '# X\n\n## Open Questions\n\n- who owns pricing?\n')
    expect(() => applyAccept(content, node, config)).toThrowError(/unresolved open questions/)
  })

  // rule-00001-AC-12.1
  it('promotes a draft whose open questions section is gone', () => {
    const { node, content } = draftPrd()
    expect(applyAccept(content, node, config).to).toBe('active')
  })
})

describe('hasOpenQuestions', () => {
  it('sees a section carrying list items', () => {
    expect(hasOpenQuestions('## Open Questions\n\n- one\n')).toBe(true)
  })

  it('matches a numbered and differently cased heading', () => {
    expect(hasOpenQuestions('## 8. OPEN QUESTIONS\n\n- one\n')).toBe(true)
  })

  it('ignores a section holding only prose', () => {
    expect(hasOpenQuestions('## Open Questions\n\nDelete this section once every question is closed.\n')).toBe(false)
  })

  it('ignores list items belonging to the next section', () => {
    expect(hasOpenQuestions('## Open Questions\n\n## Links\n\n- a link\n')).toBe(false)
  })

  it('sees no questions when the section is absent', () => {
    expect(hasOpenQuestions('# X\n\nbody\n')).toBe(false)
  })
})

/** spec-00001-FR-9 with rule-00001-BR-11 and BR-20: who may be clarified at all. */
describe('assertClarifiable', () => {
  // rule-00001-AC-20.1 — the five clarifiable types, each as its own draft
  it('allows a draft of every clarifiable type', () => {
    for (const type of ['idea', 'prd', 'spec', 'rule', 'design']) {
      const { node } = single({ id: `${type}-00001-x`, type, status: 'draft' }, '# X\n')
      expect(() => assertClarifiable(node, config)).not.toThrow()
    }
  })

  // spec-00001-AC-9.2
  it('rejects a document that is not draft', () => {
    const { node } = single({ id: 'prd-00001-x', type: 'prd', status: 'active' })
    expect(() => assertClarifiable(node, config)).toThrowError(WorkflowError)
    expect(() => assertClarifiable(node, config)).toThrowError(/applies to a draft document/)
  })

  // spec-00001-AC-9.4 with rule-00001-AC-20.2 — record carries no business question to ask
  it('rejects a draft of a type that is not clarifiable', () => {
    const { node } = single({ id: 'record-00001-x', type: 'record', status: 'draft' })
    expect(() => assertClarifiable(node, config)).toThrowError(/does not apply to a record document/)
  })

  it('rejects an anomalous document', () => {
    const { node } = single({ id: 'nope', type: 'prd', status: 'draft' })
    expect(() => assertClarifiable(node, config)).toThrowError(/front matter problems/)
  })
})

/** spec-00001-FR-50 and FR-51 with rule-00001-BR-23: who may be audited at all. */
describe('assertAuditable', () => {
  // rule-00001-AC-23.1 — the three auditable types, each as its own draft
  it('allows a draft of every auditable type', () => {
    for (const type of ['spec', 'rule', 'design']) {
      const { node } = single({ id: `${type}-00001-x`, type, status: 'draft' }, '# X\n')
      expect(() => assertAuditable(node, config)).not.toThrow()
    }
  })

  // spec-00001-AC-51.1 with rule-00001-AC-23.2 — prd has no auditable structure
  it('rejects a draft of a type that is not auditable', () => {
    const { node } = single({ id: 'prd-00001-x', type: 'prd', status: 'draft' }, '# X\n')
    expect(() => assertAuditable(node, config)).toThrowError(WorkflowError)
    expect(() => assertAuditable(node, config)).toThrowError(/does not apply to a prd document/)
  })

  // spec-00001-AC-51.2 with rule-00001-AC-23.3
  it('rejects an auditable type that is no longer a draft', () => {
    const { node } = single({ id: 'spec-00001-x', type: 'spec', status: 'active' })
    expect(() => assertAuditable(node, config)).toThrowError(/applies to a draft document/)
  })

  // spec-00001-AC-51.3
  it('rejects an anomalous document', () => {
    const { node } = single({ id: 'nope', type: 'spec', status: 'draft' })
    expect(() => assertAuditable(node, config)).toThrowError(/front matter problems/)
  })
})

/** spec-00005-FR-1 with rule-00001-BR-21: ask is not a review action. */
describe('assertAskable', () => {
  // spec-00005-AC-1.3 with rule-00001-AC-21.1 — any type, any status
  it('allows a document of any type in any status', () => {
    for (const front of [
      { id: 'record-00001-x', type: 'record', status: 'active' },
      { id: 'prd-00001-x', type: 'prd', status: 'draft' },
      { id: 'plan-00001-x', type: 'plan', status: 'resolved' },
    ]) {
      expect(() => assertAskable(single(front).node)).not.toThrow()
    }
  })

  // spec-00005-AC-7.2
  it('rejects an anomalous document', () => {
    const { node } = single({ id: 'nope', type: 'prd', status: 'draft' })
    expect(() => assertAskable(node)).toThrowError(WorkflowError)
    expect(() => assertAskable(node)).toThrowError(/front matter problems/)
  })
})

describe('nextStepsFor', () => {
  // spec-00001-AC-10.1
  it('offers exactly spec for a prd', () => {
    expect(nextStepsFor(draftPrd().node, config)).toEqual([{ next: 'spec', carry: 'parent' }])
  })

  // spec-00001-AC-10.2 with rule-00001-AC-13.1
  it('offers both prd and spec for an idea', () => {
    const { node } = single({ id: 'idea-00001-x', type: 'idea', status: 'active' })
    expect(nextStepsFor(node, config)).toEqual([
      { next: 'prd', carry: 'parent' },
      { next: 'spec', carry: 'parent' },
    ])
  })

  // spec-00001-AC-10.3 with rule-00001-AC-17.1
  it('offers nothing for a type the flow config does not carry', () => {
    const { node } = single({ id: 'record-00001-x', type: 'record', status: 'active' })
    expect(nextStepsFor(node, config)).toEqual([])
  })

  it('offers nothing for an anomalous document', () => {
    const { node } = single({ id: 'nope', type: 'prd', status: 'draft' })
    expect(nextStepsFor(node, config)).toEqual([])
  })

  // rule-00001-AC-15.1 with AC-15.2 and AC-15.3
  it('offers rule, design, and plan for a spec, each carrying its own relation', () => {
    const { node } = single({ id: 'spec-00001-x', type: 'spec', status: 'active' })
    expect(nextStepsFor(node, config)).toEqual([
      { next: 'rule', carry: 'informs' },
      { next: 'design', carry: 'informs' },
      { next: 'plan', carry: 'implements' },
    ])
  })

  // rule-00001-AC-16.1 with AC-16.2 and AC-16.3: the implementation phase's three
  // next steps, each carrying its own relation
  it('offers task, issue, and record for a plan, each carrying its own relation', () => {
    const { node } = single({ id: 'plan-00001-x', type: 'plan', status: 'open' })
    expect(nextStepsFor(node, config)).toEqual([
      { next: 'task', carry: 'parent' },
      { next: 'issue', carry: 'blocks' },
      { next: 'record', carry: 'parent' },
    ])
  })
})

describe('allocateNumber and idPrefix', () => {
  // rule-00001-AC-18.1 with spec-00001-AC-11.2
  it('takes the next number after the highest in use', () => {
    const graph = readGraph(
      makeDocsDir({ 'prd/a.md': doc({ id: 'prd-00001-x', type: 'prd', status: 'active' }) }),
      config,
    )
    expect(allocateNumber(graph, 'prd')).toBe(2)
    expect(idPrefix('prd', allocateNumber(graph, 'prd'))).toBe('prd-00002-')
  })

  // rule-00001-AC-18.2
  it('starts at one for a type with no documents', () => {
    const graph = readGraph(makeDocsDir({}), config)
    expect(idPrefix('task', allocateNumber(graph, 'task'))).toBe('task-00001-')
  })
})

/**
 * The issue gate of spec-00007-FR-4, as one table: a document is annotatable with
 * an issue when a cowrite may be started on it as it stands (rule-00001-BR-29) or
 * one legal transition away (BR-3). `cowriteRevision` names that transition, and
 * it is the one a unified submit makes before the session.
 */
describe('issueEligible and cowriteRevision', () => {
  const cases: Array<[string, string, boolean, 'draft' | undefined]> = [
    ['prd', 'draft', true, undefined],
    ['prd', 'active', true, 'draft'],
    ['prd', 'archived', false, undefined],
    ['plan', 'draft', true, undefined],
    ['plan', 'open', true, undefined],
    ['plan', 'resolved', false, undefined],
    ['plan', 'wontfix', false, undefined],
    ['plan', 'archived', false, undefined],
  ]

  // spec-00007-AC-4.1, AC-4.3, AC-4.4, AC-4.5 — every status of both kinds
  for (const [type, status, eligible, revision] of cases) {
    it(`reads a ${status} ${type} as ${eligible ? 'annotatable' : 'not annotatable'} with an issue`, () => {
      const { node } = single({ id: `${type}-00001-x`, type, status }, '# X\n')

      expect(issueEligible(node, config)).toBe(eligible)
      expect(cowriteRevision(node, config)).toBe(revision)
    })
  }

  // spec-00007-AC-4.6 — an anomalous document is annotatable with neither type
  it('reads an anomalous document as annotatable with nothing at all', () => {
    const { node } = single({ id: 'nope', type: 'prd', status: 'draft' }, '# X\n')

    expect(node.ok).toBe(false)
    expect(issueEligible(node, config)).toBe(false)
    expect(cowriteRevision(node, config)).toBeUndefined()
  })
})

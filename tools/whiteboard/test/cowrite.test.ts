import { describe, expect, it } from 'vitest'
import { ITEM_GRAMMAR } from '../src/advance.ts'
import {
  type ReferenceCandidate,
  cowriteInstruction,
  guardFrontMatter,
  judgeReferences,
  materialLines,
  prefilledTemplate,
} from '../src/cowrite.ts'
import { type DocGraph, type DocNode, readGraph } from '../src/docRepository.ts'
import { WorkflowError } from '../src/workflow.ts'
import { cowriteConfig, doc, makeDocsDir } from './helpers.ts'

const config = cowriteConfig()

const TASK = {
  docPath: 'integration/integration-00001-cli.md',
  docType: 'integration',
  readmePath: 'integration/README.md',
  referenceStart: 4,
  materialLines: [],
}

/** A tree to resolve in-repo material ids against. */
function graphOn(files: Record<string, string>): DocGraph {
  return readGraph(makeDocsDir(files), config)
}

const REPO = {
  'integration/cli.md': doc({ id: 'integration-00001-cli', type: 'integration', status: 'draft' }),
  'prd/board.md': doc({ id: 'prd-00001-board', type: 'prd', status: 'active' }),
}

describe('cowriteInstruction', () => {
  /**
   * spec-00006-AC-1.1 for what the instruction has to carry: the target path and
   * type, its folder README, the write scope of rule-00001-BR-30 and the
   * distillation requirement. The session start half of that criterion is asserted
   * over the route (server.test.ts).
   */
  // spec-00006-AC-1.1
  it('names the target, its folder README, the write scope, and what the materials owe', () => {
    const instruction = cowriteInstruction(TASK)

    expect(instruction).toContain('cowrite session')
    expect(instruction).toContain('integration/integration-00001-cli.md')
    expect(instruction).toContain('one integration document')
    expect(instruction).toContain('integration/README.md')
    expect(instruction).toContain('never its front matter id or status line')
    expect(instruction).toContain('rule-00001-BR-30')
    expect(instruction).toContain('supports a conclusion goes into the body of the document')
    expect(instruction).toContain('Change nothing outside the docs tree.')
  })

  // The reference requisites of design-00001 §11.1: without them the collapse
  // filter would delete what the session produced (rule-00001-BR-30).
  it('gives the reference requisites: both files, the numbering, the status, the canonical path', () => {
    const instruction = cowriteInstruction(TASK)

    expect(instruction).toContain('reference/TEMPLATE.md')
    expect(instruction).toContain('reference/README.md')
    expect(instruction).toContain('reference-<nnnnn>-<slug>')
    expect(instruction).toContain('reference-00004-')
    expect(instruction).toContain('set status: draft')
    expect(instruction).toContain('file it at reference/<id>.md')
  })

  // spec-00001-FR-41's grammar段, reused: the type that has one is told it, and
  // the type that has none is told nothing.
  it('restates the item grammar of a type that has one, and nothing for a type that has none', () => {
    const withGrammar = cowriteInstruction({
      ...TASK,
      docPath: 'spec/board.md',
      docType: 'spec',
      readmePath: 'spec/README.md',
      grammar: ITEM_GRAMMAR.spec,
    })

    expect(withGrammar).toContain('机器可读形态')
    expect(withGrammar).toContain('**spec-<n>-FR-<i>**')
    expect(cowriteInstruction(TASK)).not.toContain('机器可读形态')
  })

  // The status is the session's to keep, not to move (rule-00001-BR-28).
  it('tells the session to leave the document in the status it is in', () => {
    expect(cowriteInstruction(TASK)).toContain('in the status it is in')
  })
})

describe('materialLines', () => {
  // spec-00006-AC-3.1
  it('carries a pasted text and a URL into the instruction', () => {
    const materials = { text: 'the owner pasted this\nover two lines', urls: ['https://example.test/case'] }

    const instruction = cowriteInstruction({ ...TASK, materialLines: materialLines(materials, graphOn(REPO)) })

    expect(instruction).toContain('the owner pasted this\nover two lines')
    expect(instruction).toContain('https://example.test/case')
  })

  // spec-00006-AC-3.2
  it('carries an in-repo document id with the path it resolves to, and an outside path as given', () => {
    const materials = { docIds: ['prd-00001-board'], paths: ['/Users/owner/notes/case.md'] }

    const instruction = cowriteInstruction({ ...TASK, materialLines: materialLines(materials, graphOn(REPO)) })

    expect(instruction).toContain('prd-00001-board at prd/board.md')
    expect(instruction).toContain('/Users/owner/notes/case.md')
  })

  // rule-00001-AC-28.3 — all three ways of giving a material reach the one task input
  it('carries a pasted text, an in-repo id and a URL together', () => {
    const materials = { text: 'pasted evidence', docIds: ['prd-00001-board'], urls: ['https://example.test/a'] }

    const instruction = cowriteInstruction({ ...TASK, materialLines: materialLines(materials, graphOn(REPO)) })

    expect(instruction).toContain('pasted evidence')
    expect(instruction).toContain('prd-00001-board')
    expect(instruction).toContain('https://example.test/a')
  })

  // spec-00006-AC-3.3
  it('leaves the materials segment out when nothing was given', () => {
    for (const materials of [undefined, {}, { text: '   ' }]) {
      expect(materialLines(materials, graphOn(REPO))).toEqual([])
      expect(cowriteInstruction({ ...TASK, materialLines: materialLines(materials, graphOn(REPO)) })).not.toContain(
        'The materials the owner gave you',
      )
    }
  })

  /**
   * The permission prompting is the CLI's own (spec-00006-FR-7, design-00001
   * §11.1): the instruction says so, and says a refusal ends nothing — the board
   * neither pre-authorises nor answers those prompts.
   */
  // spec-00006-AC-7.3
  it('says that reading anything outside the repo goes through the agent’s own permission mechanism', () => {
    const lines = materialLines({ urls: ['https://example.test/a'] }, graphOn(REPO)).join('\n')

    expect(lines).toContain('your own permission mechanism')
    expect(lines).toContain('a refusal or an unreachable material ends nothing')
  })

  // An id nobody can resolve is a material the agent would go looking for and
  // never find, so it is refused at the door rather than passed on.
  it('refuses a document id that names nothing in this repo', () => {
    expect(() => materialLines({ docIds: ['prd-00009-nope'] }, graphOn(REPO))).toThrowError(WorkflowError)
  })

  // Only the outside-the-repo materials carry the permission note.
  it('says nothing about permissions when every material is in the repo', () => {
    const lines = materialLines({ docIds: ['prd-00001-board'] }, graphOn(REPO)).join('\n')

    expect(lines).not.toContain('permission mechanism')
  })
})

describe('guardFrontMatter', () => {
  const BODY = '# Title\n\nthe agent wrote this\n'

  // The guard itself: the status goes back, the body the session wrote stays
  it('puts a changed status line back and leaves the body as the session wrote it', () => {
    const written = doc({ id: 'prd-00001-x', type: 'prd', status: 'active' }, BODY)

    const guarded = guardFrontMatter(written, 'prd-00001-x', 'draft')

    expect(guarded.content).toContain('status: draft')
    expect(guarded.content).not.toContain('status: active')
    expect(guarded.content).toContain('the agent wrote this')
    expect(guarded.problem).toBeUndefined()
  })

  it('puts a changed id line back', () => {
    const written = doc({ id: 'prd-00007-renamed', type: 'prd', status: 'draft' }, BODY)

    expect(guardFrontMatter(written, 'prd-00001-x', 'draft').content).toContain('id: prd-00001-x')
  })

  it('puts a removed id or status line back into the block it belongs to', () => {
    const written = `---\ntype: prd\n---\n\n${BODY}`

    const guarded = guardFrontMatter(written, 'prd-00001-x', 'draft')

    expect(guarded.content).toContain('id: prd-00001-x')
    expect(guarded.content).toContain('status: draft')
    expect(guarded.content).toContain('type: prd')
  })

  it('reports a file whose front matter block is gone, rather than inventing one', () => {
    const guarded = guardFrontMatter(BODY, 'prd-00001-x', 'draft')

    expect(guarded.content).toBe(BODY)
    expect(guarded.problem).toMatch(/front matter block is gone/)
  })

  it('reports a file whose front matter block was never closed', () => {
    expect(guardFrontMatter(`---\nid: prd-00001-x\n`, 'prd-00001-x', 'draft').problem).toBeDefined()
  })
})

describe('prefilledTemplate', () => {
  // The create form files the document itself (spec-00006-FR-2), so the three
  // lines `GET /api/create` prefills are filled in here instead.
  it('fills the id, type and status of the template’s own front matter', () => {
    const template = '---\nid: idea-00000-slug\ntype: idea\nstatus: draft\nparent:\n---\n\n# Title\n'

    const filled = prefilledTemplate(template, 'idea-00003-board', 'idea')

    expect(filled).toContain('id: idea-00003-board')
    expect(filled).toContain('type: idea')
    expect(filled).toContain('status: draft')
    expect(filled).toContain('parent:')
    expect(filled).toContain('# Title')
  })

  it('gives a template with no front matter the minimal one a document needs', () => {
    const filled = prefilledTemplate('# Title\n', 'idea-00003-board', 'idea')

    expect(filled.startsWith('---\nid: idea-00003-board\ntype: idea\nstatus: draft\n---\n')).toBe(true)
    expect(filled).toContain('# Title')
  })

  it('fills lines a template leaves out altogether', () => {
    const filled = prefilledTemplate('---\nparent:\n---\n\n# Title\n', 'idea-00003-board', 'idea')

    for (const line of ['id: idea-00003-board', 'type: idea', 'status: draft', 'parent:']) {
      expect(filled).toContain(line)
    }
    expect(filled.split('---')).toHaveLength(3)
  })
})

/**
 * The 合式 judgment of spec-00006-FR-6, read over the set (design-00001 §11.3):
 * the per-file readings first, the numbering after them — and the numbering is a
 * property of the set, because rule-00001-BR-18 read per file would make the
 * second reference of one session illegal by construction.
 */
describe('judgeReferences', () => {
  function candidate(id: string, status = 'draft', type = 'reference', path = `reference/${id}.md`): ReferenceCandidate {
    const node: DocNode = {
      id,
      path,
      type,
      status,
      title: id,
      relations: {},
      ok: true,
      problems: [],
    }
    return { path: `docs/${path}`, node }
  }

  // spec-00006-AC-8.4 — several well-formed references are one contiguous run
  it('passes a run of well-formed references starting one above the highest existing number', () => {
    const verdict = judgeReferences([candidate('reference-00004-a'), candidate('reference-00005-b')], new Set(), 3)

    expect(verdict.wellFormed).toEqual(['docs/reference/reference-00004-a.md', 'docs/reference/reference-00005-b.md'])
    expect(verdict.rejected).toEqual([])
  })

  it('rejects a file the board could read as no document at all', () => {
    const verdict = judgeReferences([{ path: 'docs/reference/notes.txt' }], new Set(), 0)

    expect(verdict.wellFormed).toEqual([])
    expect(verdict.rejected[0]!.reason).toMatch(/no document/)
  })

  // rule-00001-AC-30.3's reading inside the folder: the type has to be reference
  it('rejects a document of another type, a status that is not draft, and a non-canonical path', () => {
    const others = [
      candidate('reference-00001-a', 'draft', 'record'),
      candidate('reference-00001-b', 'active'),
      candidate('reference-00001-c', 'draft', 'reference', 'reference/elsewhere.md'),
    ]

    const verdict = judgeReferences(others, new Set(), 0)

    expect(verdict.wellFormed).toEqual([])
    expect(verdict.rejected.map((one) => one.reason)).toEqual([
      'its type is "record", not reference',
      'its status is "active", not draft',
      'it is at reference/elsewhere.md rather than at its canonical reference/reference-00001-c.md',
    ])
  })

  it('rejects an id that is not <type>-<nnnnn>-<slug>', () => {
    const verdict = judgeReferences([candidate('reference-1-a')], new Set(), 0)

    expect(verdict.rejected[0]!.reason).toMatch(/is not <type>-<nnnnn>-<slug>/)
  })

  // rule-00001-AC-30.4 — an id another document already declares does not land
  it('rejects an id another document already declares', () => {
    const verdict = judgeReferences([candidate('reference-00001-a')], new Set(['reference-00001-a']), 0)

    expect(verdict.wellFormed).toEqual([])
    expect(verdict.rejected[0]!.reason).toMatch(/already the id of another document/)
  })

  // The numbering belongs to the set, so a set that took the wrong numbers is
  // rejected whole: no one member of it is the one that was wrong.
  it('rejects the whole set when its numbers do not run on from the highest existing one', () => {
    const verdict = judgeReferences([candidate('reference-00004-a'), candidate('reference-00006-b')], new Set(), 3)

    expect(verdict.wellFormed).toEqual([])
    expect(verdict.rejected.map((one) => one.reason)).toEqual([
      "its number is not part of the run from 4 this session's references had to take",
      "its number is not part of the run from 4 this session's references had to take",
    ])
  })

  it('judges an empty set as nothing to land', () => {
    expect(judgeReferences([], new Set(), 3)).toEqual({ wellFormed: [], rejected: [] })
  })
})

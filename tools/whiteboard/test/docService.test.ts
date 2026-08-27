import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { ConflictError, DocService, GateError } from '../src/docService.ts'
import { contentHash } from '../src/docRepository.ts'
import { type CommitOutcome, type DirtySnapshot, GitLayer } from '../src/gitLayer.ts'
import { SessionBusyError, type SessionClaim, type SessionPlan } from '../src/sessionManager.ts'
import { clarifyStatePath } from '../src/sessionTasks.ts'
import { WorkflowError } from '../src/workflow.ts'
import {
  commitCount,
  cowriteConfig,
  doc,
  git,
  lastCommitFiles,
  lastCommitMessage,
  makeRepo,
  testConfig,
} from './helpers.ts'

const config = testConfig()
const DRAFT_PRD = doc({ id: 'prd-00001-x', type: 'prd', status: 'draft' }, '# X\n\nbody\n')
const DRAFT_PLAN = doc({ id: 'plan-00001-y', type: 'plan', status: 'draft' }, '# Y\n')

function serviceOn(files: Record<string, string>) {
  const { repoRoot, docsDir } = makeRepo(files)
  return { repoRoot, docsDir, service: new DocService(repoRoot, docsDir, config) }
}

function onDisk(docsDir: string, relPath: string): string {
  return readFileSync(join(docsDir, relPath), 'utf8')
}

describe('save', () => {
  // spec-00001-AC-4.1
  it('writes the edited content to disk', async () => {
    const { docsDir, service } = serviceOn({ 'prd/a.md': DRAFT_PRD })
    const base = service.read('prd-00001-x')

    await service.save('prd-00001-x', `${base.content}edited\n`, base.hash)

    expect(onDisk(docsDir, 'prd/a.md')).toBe(`${DRAFT_PRD}edited\n`)
  })

  // spec-00001-AC-14.1
  it('commits the edit naming the action and the document id', async () => {
    const { repoRoot, service } = serviceOn({ 'prd/a.md': DRAFT_PRD })
    const base = service.read('prd-00001-x')

    const result = await service.save('prd-00001-x', `${base.content}edited\n`, base.hash)

    expect(result.committed).toBe(true)
    expect(lastCommitMessage(repoRoot)).toBe('wb(edit): prd-00001-x')
  })

  // spec-00001-AC-14.2
  it('leaves an unrelated dirty file out of the commit', async () => {
    const { repoRoot, docsDir, service } = serviceOn({ 'prd/a.md': DRAFT_PRD, 'idea/b.md': DRAFT_PLAN })
    writeFileSync(join(docsDir, 'idea/b.md'), `${DRAFT_PLAN}dirty\n`)
    const base = service.read('prd-00001-x')

    await service.save('prd-00001-x', `${base.content}edited\n`, base.hash)

    expect(lastCommitFiles(repoRoot)).toEqual(['docs/prd/a.md'])
  })

  // spec-00001-AC-5.1 and AC-5.2
  it('rejects a save whose base no longer matches, keeping the external version', async () => {
    const { docsDir, service } = serviceOn({ 'prd/a.md': DRAFT_PRD })
    const base = service.read('prd-00001-x')
    writeFileSync(join(docsDir, 'prd/a.md'), `${DRAFT_PRD}from an agent\n`)

    await expect(service.save('prd-00001-x', 'mine\n', base.hash)).rejects.toThrowError(ConflictError)
    expect(onDisk(docsDir, 'prd/a.md')).toBe(`${DRAFT_PRD}from an agent\n`)
  })

  // spec-00001-AC-5.3
  it('rejects a save whose file was deleted', async () => {
    const { docsDir, service } = serviceOn({ 'prd/a.md': DRAFT_PRD })
    const base = service.read('prd-00001-x')
    rmSync(join(docsDir, 'prd/a.md'))

    await expect(service.save('prd-00001-x', 'mine\n', base.hash)).rejects.toThrowError(/refresh the board/)
  })

  it('accepts a save that matches the current hash exactly', async () => {
    const { service } = serviceOn({ 'prd/a.md': DRAFT_PRD })
    expect(service.read('prd-00001-x').hash).toBe(contentHash(DRAFT_PRD))
  })
})

describe('changeStatus', () => {
  it('writes a legal transition and reports the new status', async () => {
    const { docsDir, repoRoot, service } = serviceOn({ 'prd/a.md': DRAFT_PRD })

    const result = await service.changeStatus('prd-00001-x', 'active')

    expect(result).toEqual({ committed: true, status: 'active' })
    expect(onDisk(docsDir, 'prd/a.md')).toContain('status: active')
    expect(lastCommitMessage(repoRoot)).toBe('wb(status): prd-00001-x')
  })

  // spec-00001-AC-7.1
  it('rejects an illegal transition and leaves the file untouched', async () => {
    const { docsDir, repoRoot, service } = serviceOn({ 'plan/a.md': DRAFT_PLAN })
    const before = commitCount(repoRoot)

    await expect(service.changeStatus('plan-00001-y', 'resolved')).rejects.toThrowError(WorkflowError)
    expect(onDisk(docsDir, 'plan/a.md')).toBe(DRAFT_PLAN)
    expect(commitCount(repoRoot)).toBe(before)
  })
})

/**
 * The promotion gate on the status path (spec-00002-FR-1 and FR-2 with
 * rule-00001-BR-12, issue-00015): the gate is on the transition, not on the
 * accept button, so a promotion out of `draft` meets the same reading whichever
 * action produced it.
 */
describe('the promotion gate', () => {
  const OPEN_QUESTIONS = '# Doc\n\n## Open Questions\n\n- which failure mode is unstated?\n'

  // spec-00002-AC-1.1
  it('refuses to promote a draft with open questions on the status path', async () => {
    const file = doc({ id: 'spec-00001-b', type: 'spec', status: 'draft' }, OPEN_QUESTIONS)
    const { docsDir, repoRoot, service } = serviceOn({ 'spec/b.md': file })
    const before = commitCount(repoRoot)

    await expect(service.changeStatus('spec-00001-b', 'active')).rejects.toThrowError(WorkflowError)
    expect(onDisk(docsDir, 'spec/b.md')).toBe(file)
    expect(commitCount(repoRoot)).toBe(before)
  })

  // spec-00002-AC-1.2
  it('refuses to promote a draft work item with open questions into open', async () => {
    const file = doc({ id: 'plan-00001-y', type: 'plan', status: 'draft' }, OPEN_QUESTIONS)
    const { docsDir, repoRoot, service } = serviceOn({ 'plan/a.md': file })
    const before = commitCount(repoRoot)

    await expect(service.changeStatus('plan-00001-y', 'open')).rejects.toThrowError(WorkflowError)
    expect(onDisk(docsDir, 'plan/a.md')).toBe(file)
    expect(commitCount(repoRoot)).toBe(before)
  })

  // spec-00002-AC-1.3
  it('names the unresolved open questions in the refusal', async () => {
    const { service } = serviceOn({ 'spec/b.md': doc({ id: 'spec-00001-b', type: 'spec', status: 'draft' }, OPEN_QUESTIONS) })

    await expect(service.changeStatus('spec-00001-b', 'active')).rejects.toThrowError(
      /spec-00001-b has unresolved open questions/,
    )
  })

  // spec-00002-AC-1.4 — the gate is a pure reading, so it leaves nothing behind to weaken it
  it('refuses the same promotion again, still writing nothing', async () => {
    const file = doc({ id: 'spec-00001-b', type: 'spec', status: 'draft' }, OPEN_QUESTIONS)
    const { docsDir, repoRoot, service } = serviceOn({ 'spec/b.md': file })
    const before = commitCount(repoRoot)

    await expect(service.changeStatus('spec-00001-b', 'active')).rejects.toThrowError(WorkflowError)
    await expect(service.changeStatus('spec-00001-b', 'active')).rejects.toThrowError(WorkflowError)
    expect(onDisk(docsDir, 'spec/b.md')).toBe(file)
    expect(commitCount(repoRoot)).toBe(before)
  })

  // spec-00002-AC-1.5
  it('promotes a draft that has no open questions section at all', async () => {
    const { service } = serviceOn({ 'design/d.md': doc({ id: 'design-00001-d', type: 'design', status: 'draft' }, '# D\n') })

    expect((await service.changeStatus('design-00001-d', 'active')).status).toBe('active')
  })

  // spec-00002-AC-1.6 — a section whose questions are all closed carries no list item
  it('promotes a draft whose open questions section holds no list item', async () => {
    const body = '# X\n\n## Open Questions\n\nnothing left to ask.\n'
    const { service } = serviceOn({ 'prd/a.md': doc({ id: 'prd-00001-x', type: 'prd', status: 'draft' }, body) })

    expect((await service.changeStatus('prd-00001-x', 'active')).status).toBe('active')
  })

  // spec-00002-AC-1.7 — one reading, so the two paths cannot disagree
  it('refuses on the status path what the accept path already refused', async () => {
    const { service } = serviceOn({ 'prd/a.md': doc({ id: 'prd-00001-x', type: 'prd', status: 'draft' }, OPEN_QUESTIONS) })

    await expect(service.review('prd-00001-x', { action: 'accept' })).rejects.toThrowError(
      /unresolved open questions/,
    )
    await expect(service.changeStatus('prd-00001-x', 'active')).rejects.toThrowError(
      /unresolved open questions/,
    )
  })
})

/**
 * spec-00002-FR-2: which transitions the promotion gate is none of the business
 * of. Each of these carries unresolved open questions and goes through all the
 * same — the gate reads the target status, not the presence of questions.
 */
describe('transitions the promotion gate leaves alone', () => {
  const OPEN_QUESTIONS = '# Doc\n\n## Open Questions\n\n- still unanswered?\n'

  // spec-00002-AC-2.1
  it('lets a draft work item reach wontfix', async () => {
    const { service } = serviceOn({ 'issue/i.md': doc({ id: 'issue-00001-i', type: 'issue', status: 'draft' }, OPEN_QUESTIONS) })

    expect((await service.changeStatus('issue-00001-i', 'wontfix')).status).toBe('wontfix')
  })

  // spec-00002-AC-2.2 — the revision round goes the other way, so it is no promotion
  it('lets an active living doc go back to draft', async () => {
    const { service } = serviceOn({ 'spec/b.md': doc({ id: 'spec-00001-b', type: 'spec', status: 'active' }, OPEN_QUESTIONS) })

    expect((await service.changeStatus('spec-00001-b', 'draft')).status).toBe('draft')
  })

  // spec-00002-AC-2.3
  it('lets a draft reach archived when another document supersedes it', async () => {
    const { service } = serviceOn({
      'design/d.md': doc({ id: 'design-00001-d', type: 'design', status: 'draft' }, OPEN_QUESTIONS),
      'design/e.md': doc(
        { id: 'design-00002-e', type: 'design', status: 'active', supersedes: '[design-00001-d]' },
        '# E\n',
      ),
    })

    expect((await service.changeStatus('design-00001-d', 'archived')).status).toBe('archived')
  })

  // spec-00002-AC-2.4
  it('lets an open plan whose scope is verified reach resolved', async () => {
    const spec = doc(
      { id: 'spec-00001-b', type: 'spec', status: 'active' },
      [
        '# Spec',
        '',
        '- **spec-00001-FR-1** (Event) the system shall do the thing',
        '',
        '- **spec-00001-AC-1.1** (spec-00001-FR-1)',
        '  Given a board',
        '  When it loads',
        '  Then it works',
        '',
      ].join('\n'),
    )
    const { service } = serviceOn({
      'spec/b.md': spec,
      'plan/a.md': doc(
        { id: 'plan-00001-y', type: 'plan', status: 'open', implements: '[spec-00001-FR-1]' },
        OPEN_QUESTIONS,
      ),
      'record/r.md': doc(
        { id: 'record-00001-r', type: 'record', status: 'active', parent: 'plan-00001-y' },
        ['# 验收记录', '', '| GWT id | 测试 | 结果 |', '| --- | --- | --- |', '| spec-00001-AC-1.1 | some.test.ts | pass |', ''].join('\n'),
      ),
    })

    expect((await service.changeStatus('plan-00001-y', 'resolved')).status).toBe('resolved')
  })

  // spec-00002-AC-2.5 — rule-00001-BR-12 does not roll a promoted document back
  it('leaves an already active document active when questions appear under it', () => {
    const spec = doc({ id: 'spec-00001-b', type: 'spec', status: 'active' }, '# Spec\n')
    const { docsDir, service } = serviceOn({ 'spec/b.md': spec })
    writeFileSync(join(docsDir, 'spec/b.md'), doc({ id: 'spec-00001-b', type: 'spec', status: 'active' }, OPEN_QUESTIONS))

    service.invalidate()

    expect(service.graph().nodes.find((node) => node.id === 'spec-00001-b')?.status).toBe('active')
  })
})

/**
 * The archive gate (spec-00002-FR-3 and FR-4 with rule-00001-BR-19): `archived`
 * means «replaced», so the transition waits on another document saying so. The
 * gate reads front matter declarations across the whole repo — not node health,
 * not the replacement's type or status.
 */
describe('the archive gate', () => {
  const ACTIVE_SPEC = doc({ id: 'spec-00001-b', type: 'spec', status: 'active' }, '# Spec\n')

  /** A document whose `supersedes` lists `targets`, written as a YAML flow list. */
  function replacement(id: string, type: string, status: string, targets: string[]): string {
    return doc({ id, type, status, supersedes: `[${targets.join(', ')}]` }, `# ${id}\n`)
  }

  // spec-00002-AC-3.1
  it('refuses to archive a document nothing supersedes, leaving the file alone', async () => {
    const { docsDir, repoRoot, service } = serviceOn({ 'spec/b.md': ACTIVE_SPEC })
    const before = commitCount(repoRoot)

    await expect(service.changeStatus('spec-00001-b', 'archived')).rejects.toThrowError(WorkflowError)
    expect(onDisk(docsDir, 'spec/b.md')).toBe(ACTIVE_SPEC)
    expect(commitCount(repoRoot)).toBe(before)
  })

  // spec-00002-AC-3.2
  it('names the missing supersedes pairing in the refusal', async () => {
    const { service } = serviceOn({ 'spec/b.md': ACTIVE_SPEC })

    await expect(service.changeStatus('spec-00001-b', 'archived')).rejects.toThrowError(
      /no other document declares supersedes: spec-00001-b/,
    )
  })

  // spec-00002-AC-3.3 — a work item's completion is `resolved`; `archived` still needs a replacement
  it('refuses to archive a resolved plan nothing supersedes', async () => {
    const { service } = serviceOn({
      'plan/a.md': doc({ id: 'plan-00001-y', type: 'plan', status: 'resolved' }, '# Plan\n'),
    })

    await expect(service.changeStatus('plan-00001-y', 'archived')).rejects.toThrowError(WorkflowError)
  })

  // spec-00002-AC-3.4
  it('refuses the same archive again, still writing nothing', async () => {
    const file = doc({ id: 'issue-00001-i', type: 'issue', status: 'wontfix' }, '# Issue\n')
    const { docsDir, repoRoot, service } = serviceOn({ 'issue/i.md': file })
    const before = commitCount(repoRoot)

    await expect(service.changeStatus('issue-00001-i', 'archived')).rejects.toThrowError(WorkflowError)
    await expect(service.changeStatus('issue-00001-i', 'archived')).rejects.toThrowError(WorkflowError)
    expect(onDisk(docsDir, 'issue/i.md')).toBe(file)
    expect(commitCount(repoRoot)).toBe(before)
  })

  // spec-00002-AC-4.1
  it('archives when the superseding document is itself a draft', async () => {
    const { docsDir, service } = serviceOn({
      'spec/b.md': ACTIVE_SPEC,
      'spec/c.md': replacement('spec-00002-c', 'spec', 'draft', ['spec-00001-b']),
    })

    expect((await service.changeStatus('spec-00001-b', 'archived')).status).toBe('archived')
    expect(onDisk(docsDir, 'spec/b.md')).toContain('status: archived')
  })

  // spec-00002-AC-4.2
  it('archives when the superseding document is of another type', async () => {
    const { service } = serviceOn({
      'spec/b.md': ACTIVE_SPEC,
      'design/d.md': replacement('design-00001-d', 'design', 'active', ['spec-00001-b']),
    })

    expect((await service.changeStatus('spec-00001-b', 'archived')).status).toBe('archived')
  })

  // spec-00002-AC-4.3 — «another» is judged by path, so a self-declaration pairs with nobody
  it('refuses to archive a document that only supersedes itself', async () => {
    const { docsDir, service } = serviceOn({
      'spec/b.md': replacement('spec-00001-b', 'spec', 'active', ['spec-00001-b']),
    })

    await expect(service.changeStatus('spec-00001-b', 'archived')).rejects.toThrowError(WorkflowError)
    expect(onDisk(docsDir, 'spec/b.md')).toContain('status: active')
  })

  // spec-00002-AC-4.4
  it('archives when two documents both supersede it', async () => {
    const { service } = serviceOn({
      'spec/b.md': ACTIVE_SPEC,
      'spec/c.md': replacement('spec-00002-c', 'spec', 'active', ['spec-00001-b']),
      'spec/d.md': replacement('spec-00003-d', 'spec', 'active', ['spec-00001-b']),
    })

    expect((await service.changeStatus('spec-00001-b', 'archived')).status).toBe('archived')
  })

  // spec-00002-AC-4.5
  it('archives when the superseding document also replaces two others', async () => {
    const { service } = serviceOn({
      'spec/b.md': ACTIVE_SPEC,
      'spec/c.md': replacement('spec-00002-c', 'spec', 'active', [
        'spec-00003-gone',
        'spec-00001-b',
        'spec-00004-also-gone',
      ]),
    })

    expect((await service.changeStatus('spec-00001-b', 'archived')).status).toBe('archived')
  })

  // spec-00002-AC-4.6 — the pairing reads the declaration, never the declaring node's health
  it('archives when the superseding document is itself an anomalous node', async () => {
    const { service } = serviceOn({
      'spec/b.md': ACTIVE_SPEC,
      'spec/c.md': replacement('spec-00002-c', 'spec', 'nonsense', ['spec-00001-b']),
    })
    const declarer = service.graph().nodes.find((node) => node.id === 'spec-00002-c')
    expect(declarer?.ok).toBe(false)

    expect((await service.changeStatus('spec-00001-b', 'archived')).status).toBe('archived')
  })
})

describe('review', () => {
  // spec-00001-AC-8.1 and AC-14.3
  it('accepts a draft living doc into active and commits it', async () => {
    const { docsDir, repoRoot, service } = serviceOn({ 'prd/a.md': DRAFT_PRD })

    const result = await service.review('prd-00001-x', { action: 'accept' })

    expect(result.status).toBe('active')
    expect(onDisk(docsDir, 'prd/a.md')).toContain('status: active')
    expect(lastCommitMessage(repoRoot)).toBe('wb(accept): prd-00001-x')
  })

  // spec-00001-AC-8.2
  it('accepts a draft work item into open', async () => {
    const { service } = serviceOn({ 'plan/a.md': DRAFT_PLAN })
    expect((await service.review('plan-00001-y', { action: 'accept' })).status).toBe('open')
  })

  // spec-00001-AC-8.3
  it('rejects accepting a document that is already active', async () => {
    const { service } = serviceOn({ 'prd/a.md': doc({ id: 'prd-00001-x', type: 'prd', status: 'active' }) })
    await expect(service.review('prd-00001-x', { action: 'accept' })).rejects.toThrowError(/applies to a draft/)
  })

  // spec-00001-AC-8.4
  it('rejects accepting a draft that carries unresolved open questions', async () => {
    const { docsDir, service } = serviceOn({
      'prd/a.md': doc({ id: 'prd-00001-x', type: 'prd', status: 'draft' }, '# X\n\n## Open Questions\n\n- who?\n'),
    })

    await expect(service.review('prd-00001-x', { action: 'accept' })).rejects.toThrowError(/unresolved open questions/)
    expect(onDisk(docsDir, 'prd/a.md')).toContain('status: draft')
  })

  /**
   * Clarify is a session now, so the write path knows one review action only
   * (spec-00001-FR-9 as amended by decision-00006). An action it does not know
   * is refused rather than read as an accept.
   */
  it('rejects a review action it does not know', async () => {
    const { docsDir, service } = serviceOn({ 'prd/a.md': DRAFT_PRD })

    await expect(service.review('prd-00001-x', { action: 'clarify' } as never)).rejects.toThrowError(
      /is not a review action/,
    )
    expect(onDisk(docsDir, 'prd/a.md')).toBe(DRAFT_PRD)
  })

  // spec-00001-AC-46.6 — the accept is the end of that round of clarify
  it('drops the clarify state file the document was left with', async () => {
    const { repoRoot, service } = serviceOn({ 'prd/a.md': DRAFT_PRD })
    mkdirSync(join(repoRoot, '.whiteboard/clarify'), { recursive: true })
    const statePath = join(repoRoot, clarifyStatePath('prd-00001-x'))
    writeFileSync(statePath, '{"answered":2}')

    await service.review('prd-00001-x', { action: 'accept' })

    expect(existsSync(statePath)).toBe(false)
  })

  it('accepts a document that has no clarify state file to drop', async () => {
    const { repoRoot, service } = serviceOn({ 'prd/a.md': DRAFT_PRD })

    expect((await service.review('prd-00001-x', { action: 'accept' })).status).toBe('active')
    expect(existsSync(join(repoRoot, clarifyStatePath('prd-00001-x')))).toBe(false)
  })

  // spec-00001-AC-19.1
  it('rejects an action on a document whose file was deleted, without committing', async () => {
    const { docsDir, repoRoot, service } = serviceOn({ 'prd/a.md': DRAFT_PRD })
    rmSync(join(docsDir, 'prd/a.md'))
    const before = commitCount(repoRoot)

    await expect(service.review('prd-00001-x', { action: 'accept' })).rejects.toThrowError(/refresh the board/)
    expect(commitCount(repoRoot)).toBe(before)
  })

  it('rejects an action whose file vanished after the graph was read', async () => {
    const { repoRoot, docsDir, service } = serviceOn({ 'prd/a.md': DRAFT_PRD })
    const stale = service.graph()
    rmSync(join(docsDir, 'prd/a.md'))
    const staleService = new (class extends DocService {
      override graph() {
        return stale
      }
    })(repoRoot, docsDir, config)

    await expect(staleService.review('prd-00001-x', { action: 'accept' })).rejects.toThrowError(/no longer on disk/)
  })

  it('rejects an unknown document id', async () => {
    const { service } = serviceOn({ 'prd/a.md': DRAFT_PRD })
    expect(() => service.read('prd-09999-ghost')).toThrowError(/not a document in this repo/)
  })
})

// spec-00001-AC-20.1
describe('a failing commit', () => {
  it('reports the error and keeps the written file', async () => {
    const { repoRoot, docsDir, service } = serviceOn({ 'prd/a.md': DRAFT_PRD })
    git(repoRoot, 'config', 'user.email', '')
    git(repoRoot, 'config', 'user.name', '')

    const result = await service.changeStatus('prd-00001-x', 'active')

    expect(result.committed).toBe(false)
    expect(result.error).toBeTruthy()
    expect(onDisk(docsDir, 'prd/a.md')).toContain('status: active')
  })
})

describe('commitSessionChanges', () => {
  // spec-00001-AC-14.4
  it('commits everything the session left under docs', async () => {
    const { repoRoot, docsDir, service } = serviceOn({ 'prd/a.md': DRAFT_PRD })
    const before = service.snapshotDocs()
    mkdirSync(join(docsDir, 'spec'))
    writeFileSync(join(docsDir, 'spec/new.md'), doc({ id: 'spec-00001-z', type: 'spec', status: 'draft' }))

    const result = await service.commitSessionChanges('spec-00001-z', before)

    expect(result.committed).toBe(true)
    expect(lastCommitMessage(repoRoot)).toBe('wb(advance): spec-00001-z')
    expect(lastCommitFiles(repoRoot)).toEqual(['docs/spec/new.md'])
  })

  // spec-00001-AC-14.8 at the commit boundary: one commit per session, named
  // after the kind of session it was. Ask is not among them — since the
  // twenty-first round it makes no commit at all (spec-00005-FR-4).
  it('names the commit after the session kind', async () => {
    for (const [kind, message] of [
      ['clarify', 'wb(clarify): prd-00001-x'],
      ['audit', 'wb(audit): prd-00001-x'],
    ] as const) {
      const { repoRoot, docsDir, service } = serviceOn({ 'prd/a.md': DRAFT_PRD })
      const before = service.snapshotDocs()
      writeFileSync(join(docsDir, 'prd/a.md'), `${DRAFT_PRD}\n## Open Questions\n\n- who owns pricing?\n`)

      expect((await service.commitSessionChanges('prd-00001-x', before, kind)).committed).toBe(true)
      expect(lastCommitMessage(repoRoot)).toBe(message)
    }
  })

  it('skips the commit when the session changed nothing', async () => {
    const { repoRoot, service } = serviceOn({ 'prd/a.md': DRAFT_PRD })
    const commits = commitCount(repoRoot)
    const before = service.snapshotDocs()

    expect(await service.commitSessionChanges('prd-00001-x', before)).toEqual({ committed: false })
    expect(commitCount(repoRoot)).toBe(commits)
  })

  // spec-00001-AC-14.5 — the file that was already dirty is nobody's product (issue-00008)
  it('leaves a file that was dirty before the session out of the commit', async () => {
    const { repoRoot, docsDir, service } = serviceOn({ 'prd/a.md': DRAFT_PRD })
    writeFileSync(join(docsDir, 'prd/a.md'), `${DRAFT_PRD}dirty before the session ever started\n`)
    const before = service.snapshotDocs()
    mkdirSync(join(docsDir, 'spec'))
    writeFileSync(join(docsDir, 'spec/new.md'), doc({ id: 'spec-00001-z', type: 'spec', status: 'draft' }))

    const result = await service.commitSessionChanges('spec-00001-z', before)

    expect(result.committed).toBe(true)
    expect(lastCommitFiles(repoRoot)).toEqual(['docs/spec/new.md'])
  })

  // spec-00001-AC-14.6 — the dirty tree it inherited is not a change of its own
  it('makes no commit when only the inherited dirt is there', async () => {
    const { repoRoot, docsDir, service } = serviceOn({ 'prd/a.md': DRAFT_PRD })
    writeFileSync(join(docsDir, 'prd/a.md'), `${DRAFT_PRD}dirty before the session ever started\n`)
    const commits = commitCount(repoRoot)
    const before = service.snapshotDocs()

    expect(await service.commitSessionChanges('prd-00001-x', before)).toEqual({ committed: false })
    expect(commitCount(repoRoot)).toBe(commits)
  })

  // A rename staged before the session is dirt at both ends, and neither end is
  // the session's (design-00001 §4).
  it('leaves a rename staged before the session out of the commit', async () => {
    const { repoRoot, docsDir, service } = serviceOn({ 'prd/a.md': DRAFT_PRD })
    git(repoRoot, 'mv', 'docs/prd/a.md', 'docs/prd/moved.md')
    const before = service.snapshotDocs()
    mkdirSync(join(docsDir, 'spec'))
    writeFileSync(join(docsDir, 'spec/new.md'), doc({ id: 'spec-00001-z', type: 'spec', status: 'draft' }))

    await service.commitSessionChanges('spec-00001-z', before)

    expect(lastCommitFiles(repoRoot)).toEqual(['docs/spec/new.md'])
  })

  // design-00001 §4, the third disposition: writing into someone else's dirty
  // draft is the commonest advance there is, and it must not be read as dirt.
  it('commits a file the session went on writing into after it was already dirty', async () => {
    const { repoRoot, docsDir, service } = serviceOn({ 'prd/a.md': DRAFT_PRD })
    writeFileSync(join(docsDir, 'prd/a.md'), `${DRAFT_PRD}an unfinished draft\n`)
    const before = service.snapshotDocs()
    writeFileSync(join(docsDir, 'prd/a.md'), `${DRAFT_PRD}an unfinished draft\nand what the agent added\n`)

    const result = await service.commitSessionChanges('prd-00001-x', before)

    expect(result.committed).toBe(true)
    expect(lastCommitFiles(repoRoot)).toEqual(['docs/prd/a.md'])
  })
})

/**
 * The two rulings a session start rests on (spec-00001-FR-9 and FR-47): the
 * document is re-read from disk first, so a stale action fails instead of
 * starting a session about a document that is gone (FR-19).
 */
describe('clarifyPlan', () => {
  const DRAFT_SPEC = doc({ id: 'spec-00001-b', type: 'spec', status: 'draft', parent: 'prd-00001-x' }, '# Spec\n')

  // spec-00001-AC-9.1, and the focus line of the type per AC-48.1
  it('plans a clarify session carrying the document, its context, and its type focus line', () => {
    const { service } = serviceOn({ 'spec/b.md': DRAFT_SPEC, 'prd/a.md': DRAFT_PRD })

    const plan = service.clarifyPlan('spec-00001-b')

    expect(plan.kind).toBe('clarify')
    expect(plan.sourceId).toBe('spec-00001-b')
    expect(plan.expectation).toBeUndefined()
    expect(plan.instruction).toContain('spec/b.md')
    expect(plan.instruction).toContain('prd/a.md')
    expect(plan.instruction).toContain(config.focus.spec!)
    expect(plan.instruction).not.toContain(config.focus.idea!)
  })

  it('carries the progress an earlier session left, and asks that it not be asked again', () => {
    const { repoRoot, service } = serviceOn({ 'spec/b.md': DRAFT_SPEC })
    mkdirSync(join(repoRoot, '.whiteboard/clarify'), { recursive: true })
    writeFileSync(join(repoRoot, clarifyStatePath('spec-00001-b')), '{"answered":["who owns pricing?"]}')

    expect(service.clarifyPlan('spec-00001-b').instruction).toContain('who owns pricing?')
  })

  // spec-00001-AC-9.2
  it('refuses a document that is not draft', () => {
    const { service } = serviceOn({ 'spec/b.md': doc({ id: 'spec-00001-b', type: 'spec', status: 'active' }) })
    expect(() => service.clarifyPlan('spec-00001-b')).toThrowError(/applies to a draft/)
  })

  // spec-00001-AC-9.4
  it('refuses a draft of a type that is not clarifiable', () => {
    const { service } = serviceOn({ 'record/r.md': doc({ id: 'record-00001-r', type: 'record', status: 'draft' }) })
    expect(() => service.clarifyPlan('record-00001-r')).toThrowError(/does not apply to a record/)
  })

  // spec-00001-AC-19.2 for clarify's half of «the target is gone»
  it('refuses a document that is no longer on disk, telling the caller to refresh', () => {
    const { docsDir, service } = serviceOn({ 'spec/b.md': DRAFT_SPEC })
    rmSync(join(docsDir, 'spec/b.md'))

    expect(() => service.clarifyPlan('spec-00001-b')).toThrowError(ConflictError)
    expect(() => service.clarifyPlan('spec-00001-b')).toThrowError(/refresh the board/)
  })
})

/** spec-00001-FR-50 and FR-51: what an audit session is started with, and who may be audited. */
describe('auditPlan', () => {
  const DRAFT_DESIGN = doc({ id: 'design-00001-b', type: 'design', status: 'draft' }, '# Design\n')

  // spec-00001-AC-50.2 with rule-00001-AC-23.1
  it('plans an audit session carrying the document and its folder README', () => {
    const { service } = serviceOn({ 'design/b.md': DRAFT_DESIGN })

    const plan = service.auditPlan('design-00001-b')

    expect(plan.kind).toBe('audit')
    expect(plan.sourceId).toBe('design-00001-b')
    expect(plan.expectation).toBeUndefined()
    expect(plan.instruction).toContain('design/b.md')
    expect(plan.instruction).toContain('design/README.md')
    expect(plan.instruction).toContain('Open Questions')
  })

  // spec-00001-AC-51.1 with rule-00001-AC-23.2
  it('refuses a draft of a type that is not auditable', () => {
    const { service } = serviceOn({ 'prd/a.md': DRAFT_PRD })
    expect(() => service.auditPlan('prd-00001-x')).toThrowError(/does not apply to a prd/)
  })

  // spec-00001-AC-51.2 with rule-00001-AC-23.3
  it('refuses a document that is not draft', () => {
    const { service } = serviceOn({ 'spec/b.md': doc({ id: 'spec-00001-b', type: 'spec', status: 'active' }) })
    expect(() => service.auditPlan('spec-00001-b')).toThrowError(/applies to a draft/)
  })

  // spec-00001-AC-51.3
  it('refuses an anomalous document', () => {
    const { service } = serviceOn({ 'spec/broken.md': doc({ id: 'nope', type: 'spec', status: 'draft' }) })
    expect(() => service.auditPlan('nope')).toThrowError(/front matter problems/)
  })

  // spec-00001-AC-19.2 for audit's half of «the target is gone»
  it('refuses a document that is no longer on disk, telling the caller to refresh', () => {
    const { docsDir, service } = serviceOn({ 'design/b.md': DRAFT_DESIGN })
    rmSync(join(docsDir, 'design/b.md'))

    expect(() => service.auditPlan('design-00001-b')).toThrowError(ConflictError)
    expect(() => service.auditPlan('design-00001-b')).toThrowError(/refresh the board/)
  })
})

/**
 * spec-00001-FR-52 on the write path: the gate sits between the transition
 * ruling and the write, so a refusal leaves the file and the history alone.
 */
describe('the resolved gate', () => {
  const SPEC = doc(
    { id: 'spec-00001-b', type: 'spec', status: 'active' },
    [
      '# Spec',
      '',
      '- **spec-00001-FR-1** (Event) the system shall do the thing',
      '',
      '- **spec-00001-AC-1.1** (spec-00001-FR-1)',
      '  Given a board',
      '  When it loads',
      '  Then it works',
      '- **spec-00001-AC-1.2** (spec-00001-FR-1)',
      '  Given a board',
      '  When it reloads',
      '  Then it still works',
      '',
    ].join('\n'),
  )
  const RULE = doc(
    { id: 'rule-00001-f', type: 'rule', status: 'active' },
    [
      '# Rule',
      '',
      '- **rule-00001-BR-1** (Constraint) the first rule',
      '- **rule-00001-BR-2** (Constraint) the second rule',
      '',
      '- **rule-00001-AC-1.1** (rule-00001-BR-1)',
      '  Given a rule',
      '  When it applies',
      '  Then it holds',
      '- **rule-00001-AC-2.1** (rule-00001-BR-2)',
      '  Given the other rule',
      '  When it applies',
      '  Then it holds too',
      '',
    ].join('\n'),
  )
  const DESIGN = doc({ id: 'design-00001-b', type: 'design', status: 'active' }, '# Design\n')

  /** A record's acceptance checklist, `parent` naming the plan it closes. */
  function record(id: string, parent: string, rows: [string, string][]): string {
    return doc(
      { id, type: 'record', status: 'active', parent },
      ['# 验收记录', '', '| GWT id | 测试 | 结果 |', '| --- | --- | --- |', ...rows.map(
        ([target, result]) => `| ${target} | some.test.ts | ${result} |`,
      ), ''].join('\n'),
    )
  }

  function planImplementing(targets: string): string {
    return doc({ id: 'plan-00001-y', type: 'plan', status: 'open', implements: targets }, '# Plan\n')
  }

  const BOTH_PASS: [string, string][] = [
    ['spec-00001-AC-1.1', 'pass'],
    ['spec-00001-AC-1.2', 'pass'],
  ]

  // spec-00001-AC-52.1 with rule-00001-AC-25.1
  it('lets a plan through when the records naming it verify its whole scope', async () => {
    const { docsDir, repoRoot, service } = serviceOn({
      'spec/b.md': SPEC,
      'plan/a.md': planImplementing('[spec-00001-FR-1]'),
      'record/r.md': record('record-00001-r', 'plan-00001-y', BOTH_PASS),
    })

    const result = await service.changeStatus('plan-00001-y', 'resolved')

    expect(result).toEqual({ committed: true, status: 'resolved' })
    expect(onDisk(docsDir, 'plan/a.md')).toContain('status: resolved')
    expect(lastCommitMessage(repoRoot)).toBe('wb(status): plan-00001-y')
  })

  // spec-00001-AC-52.2 with rule-00001-AC-25.2
  it('refuses, names the item, and writes nothing when a criterion has no row', async () => {
    const { docsDir, repoRoot, service } = serviceOn({
      'spec/b.md': SPEC,
      'plan/a.md': planImplementing('[spec-00001-FR-1]'),
      'record/r.md': record('record-00001-r', 'plan-00001-y', [['spec-00001-AC-1.1', 'pass']]),
    })
    const before = commitCount(repoRoot)

    await expect(service.changeStatus('plan-00001-y', 'resolved')).rejects.toThrowError(/spec-00001-FR-1/)
    expect(onDisk(docsDir, 'plan/a.md')).toContain('status: open')
    expect(commitCount(repoRoot)).toBe(before)
  })

  // spec-00001-AC-52.3 with rule-00001-AC-25.3
  it('refuses when a row of the scope exists but did not pass', async () => {
    const { service } = serviceOn({
      'spec/b.md': SPEC,
      'plan/a.md': planImplementing('[spec-00001-FR-1]'),
      'record/r.md': record('record-00001-r', 'plan-00001-y', [
        ['spec-00001-AC-1.1', 'pass'],
        ['spec-00001-AC-1.2', 'fail'],
      ]),
    })

    await expect(service.changeStatus('plan-00001-y', 'resolved')).rejects.toThrowError(GateError)
  })

  // spec-00001-AC-52.4 with rule-00001-AC-25.4 — another plan's record is no evidence
  it('refuses when the passing rows belong to a record naming another plan', async () => {
    const { service } = serviceOn({
      'spec/b.md': SPEC,
      'plan/a.md': planImplementing('[spec-00001-FR-1]'),
      'plan/other.md': doc({ id: 'plan-00002-z', type: 'plan', status: 'open' }, '# Other\n'),
      'record/r.md': record('record-00001-r', 'plan-00002-z', BOTH_PASS),
    })

    await expect(service.changeStatus('plan-00001-y', 'resolved')).rejects.toThrowError(/spec-00001-FR-1/)
  })

  // spec-00001-AC-52.5 with rule-00001-AC-25.5
  it('refuses and names an id its scope could not resolve', async () => {
    const { service } = serviceOn({
      'spec/b.md': SPEC,
      'plan/a.md': planImplementing('[spec-00001-FR-1, spec-00001-FR-99]'),
      'record/r.md': record('record-00001-r', 'plan-00001-y', BOTH_PASS),
    })

    await expect(service.changeStatus('plan-00001-y', 'resolved')).rejects.toThrowError(/spec-00001-FR-99/)
  })

  // spec-00001-AC-52.6 with rule-00001-AC-25.6
  it('lets a plan whose scope is empty through with no evidence at all', async () => {
    const { service } = serviceOn({ 'design/b.md': DESIGN, 'plan/a.md': planImplementing('[design-00001-b]') })

    expect((await service.changeStatus('plan-00001-y', 'resolved')).status).toBe('resolved')
  })

  // spec-00001-AC-52.7 — a whole rule document in scope, one BR unverified
  it('refuses and names the one item of a whole document in scope that nothing verifies', async () => {
    const { service } = serviceOn({
      'rule/f.md': RULE,
      'plan/a.md': planImplementing('[rule-00001-f]'),
      'record/r.md': record('record-00001-r', 'plan-00001-y', [['rule-00001-AC-1.1', 'pass']]),
    })

    await expect(service.changeStatus('plan-00001-y', 'resolved')).rejects.toThrowError(/rule-00001-BR-2/)
  })

  // spec-00001-AC-52.8 — the gate is the plan's alone
  it('lets an issue reach resolved without consulting the gate', async () => {
    const { service } = serviceOn({
      'issue/i.md': doc({ id: 'issue-00001-i', type: 'issue', status: 'open', implements: '[spec-00001-FR-1]' }),
      'spec/b.md': SPEC,
    })

    expect((await service.changeStatus('issue-00001-i', 'resolved')).status).toBe('resolved')
  })

  // spec-00001-AC-52.9 — only `resolved` is gated
  it('lets a plan with an unverified scope reach wontfix', async () => {
    const { service } = serviceOn({ 'spec/b.md': SPEC, 'plan/a.md': planImplementing('[spec-00001-FR-1]') })

    expect((await service.changeStatus('plan-00001-y', 'wontfix')).status).toBe('wontfix')
  })

  // spec-00001-AC-52.10 with rule-00001-AC-25.7 — the evidence is the union
  it('lets a plan through on coverage spread across two records naming it', async () => {
    const { service } = serviceOn({
      'spec/b.md': SPEC,
      'plan/a.md': planImplementing('[spec-00001-FR-1]'),
      'record/r.md': record('record-00001-r', 'plan-00001-y', [['spec-00001-AC-1.1', 'pass']]),
      'record/s.md': record('record-00002-s', 'plan-00001-y', [['spec-00001-AC-1.2', 'pass']]),
    })

    expect((await service.changeStatus('plan-00001-y', 'resolved')).status).toBe('resolved')
  })

  // The gaps ride along on the refusal, for the board to name one by one (design-00001 §7)
  it('carries the gaps on the refusal itself', async () => {
    const { service } = serviceOn({
      'spec/b.md': SPEC,
      'plan/a.md': planImplementing('[spec-00001-FR-1, spec-00001-FR-99]'),
    })

    await expect(service.changeStatus('plan-00001-y', 'resolved')).rejects.toMatchObject({
      gaps: ['spec-00001-FR-1', 'spec-00001-FR-99'],
    })
  })
})

describe('askPlan', () => {
  const NEW_THREAD = { id: 't-1' }

  // spec-00005-AC-1.2 — the context of a first call: the document, its relation
  // documents, the read-only nature of the session, and the question after them
  it('plans a first call carrying the document, its relation paths and the question', () => {
    const { service } = serviceOn({
      'record/r.md': doc({ id: 'record-00001-r', type: 'record', status: 'active', verifies: '[prd-00001-x]' }),
      'prd/a.md': DRAFT_PRD,
    })

    const plan = service.askPlan('record-00001-r', 'why does this verify that?', NEW_THREAD)

    expect(plan.kind).toBe('ask')
    expect(plan.sourceId).toBe('record-00001-r')
    expect(plan.threadId).toBe('t-1')
    expect(plan.resumeId).toBeUndefined()
    expect(plan.expectation).toBeUndefined()
    expect(plan.instruction).toContain('record/r.md')
    expect(plan.instruction).toContain('prd/a.md')
    expect(plan.instruction).toContain('Modify no file')
    expect(plan.instruction.endsWith('why does this verify that?')).toBe(true)
  })

  // spec-00005-AC-2.1 — a follow-up resumes a conversation that was already told
  // all of that, so it carries the question and nothing else
  it('plans a follow-up carrying the question alone, with the thread’s resume id', () => {
    const { service } = serviceOn({ 'prd/a.md': DRAFT_PRD })

    const plan = service.askPlan('prd-00001-x', 'and what about pricing?', { id: 't-1', resumeId: 'cli-42' })

    expect(plan.resumeId).toBe('cli-42')
    expect(plan.instruction).toBe('and what about pricing?')
  })

  // spec-00005-AC-7.2
  it('refuses an anomalous document', () => {
    const { service } = serviceOn({ 'prd/broken.md': doc({ id: 'nope', type: 'prd', status: 'draft' }) })
    expect(() => service.askPlan('nope', 'why?', NEW_THREAD)).toThrowError(/front matter problems/)
  })

  // spec-00001-AC-19.2
  it('refuses a document that is no longer on disk, telling the caller to refresh', () => {
    const { docsDir, service } = serviceOn({ 'prd/a.md': DRAFT_PRD })
    rmSync(join(docsDir, 'prd/a.md'))

    expect(() => service.askPlan('prd-00001-x', 'why?', NEW_THREAD)).toThrowError(/refresh the board/)
  })
})

describe('transitions and nextSteps', () => {
  it('reads the workflow decisions for a document', () => {
    const { service } = serviceOn({ 'prd/a.md': DRAFT_PRD })
    expect(service.transitions('prd-00001-x')).toEqual(['active', 'archived'])
    expect(service.nextSteps('prd-00001-x')).toEqual([{ next: 'spec', carry: 'parent' }])
  })
})

/**
 * The revision round (rule-00001-BR-3 as amended, decision-00008 §2 第 1 条): an
 * active living doc goes back to `draft`, and from there every mechanism that
 * was ever about a draft applies unchanged — audit, clarify and the accept gate.
 * That the round needs no machinery of its own is the whole decision, so these
 * are the tests that say so.
 */
describe('the revision round', () => {
  const activeSpec = (body = '# Spec\n') => doc({ id: 'spec-00001-b', type: 'spec', status: 'active' }, body)

  /** An active spec taken back to draft — the transition BR-3 now allows. */
  async function reDrafted(body?: string) {
    const open = serviceOn({ 'spec/b.md': activeSpec(body) })
    const result = await open.service.changeStatus('spec-00001-b', 'draft')
    expect(result.status).toBe('draft')
    return open
  }

  // rule-00001-AC-3.1 on the write path: the transition itself is legal now
  it('takes an active living doc back to draft and commits it', async () => {
    const { docsDir, repoRoot } = await reDrafted()

    expect(onDisk(docsDir, 'spec/b.md')).toContain('status: draft')
    expect(lastCommitMessage(repoRoot)).toBe('wb(status): spec-00001-b')
  })

  // rule-00001-AC-3.2
  it('lets an audit start on the re-drafted document', async () => {
    const { service } = await reDrafted()
    expect(service.auditPlan('spec-00001-b').kind).toBe('audit')
  })

  // rule-00001-AC-3.3
  it('lets a clarify start on the re-drafted document', async () => {
    const { service } = await reDrafted()
    expect(service.clarifyPlan('spec-00001-b').kind).toBe('clarify')
  })

  // rule-00001-AC-3.4 — the accept gate of BR-12 applies to it like any draft
  it('refuses to accept it while the revision leaves open questions', async () => {
    const { docsDir, service } = await reDrafted('# Spec\n\n## Open Questions\n\n- which failure mode is unstated?\n')

    await expect(service.review('spec-00001-b', { action: 'accept' })).rejects.toThrowError(
      /unresolved open questions/,
    )
    expect(onDisk(docsDir, 'spec/b.md')).toContain('status: draft')
  })

  // rule-00001-AC-3.5 — active → draft → active, the round closed
  it('returns it to active on accept', async () => {
    const { docsDir, service } = await reDrafted()

    expect((await service.review('spec-00001-b', { action: 'accept' })).status).toBe('active')
    expect(onDisk(docsDir, 'spec/b.md')).toContain('status: active')
  })
})

/**
 * Creating a flow entry document (spec-00001-FR-53 with rule-00001-BR-26 and
 * BR-27). The prefill writes nothing; the save is the create branch of the one
 * write path (design-00001 §6), so what is checked here is its rulings.
 */
describe('newDocument and create', () => {
  const IDEA = doc({ id: 'idea-00001-x', type: 'idea', status: 'active' }, '# Idea X\n')
  const TEMPLATE = doc({ id: 'idea-00001-example-slug', type: 'idea', status: 'draft' }, '# Idea: <one line>\n')
  const newIdea = (id: string) => doc({ id, type: 'idea', status: 'draft' }, '# A new idea\n')
  // The shipped design template itself, so the body the create hands out is the
  // one the repo really drafts from (docs/design/TEMPLATE.md).
  const DESIGN_TEMPLATE = readFileSync(new URL('../../../docs/design/TEMPLATE.md', import.meta.url).pathname, 'utf8')

  // rule-00001-AC-26.1: the number is the highest plus one, the template is the type's
  it('allocates the next number and hands back the type template', () => {
    const { service } = serviceOn({ 'idea/a.md': IDEA, 'idea/TEMPLATE.md': TEMPLATE })

    expect(service.newDocument('idea')).toEqual({ idPrefix: 'idea-00002-', template: TEMPLATE })
  })

  /**
   * rule-00001-AC-26.2: design is an entry type with no upstream (BR-26 第十四轮),
   * so a repo that holds no spec at all creates one all the same — the number
   * carries on from the design folder's own highest, and the draft body is the
   * design template. The fixture declares nothing but designs on purpose.
   */
  // rule-00001-AC-26.2
  it('creates a design with no spec in the repo, drafted from the design template', async () => {
    const { repoRoot, docsDir } = makeRepo({
      'design/design-00001-a.md': doc({ id: 'design-00001-a', type: 'design', status: 'active' }, '# Design A\n'),
      'design/design-00002-b.md': doc({ id: 'design-00002-b', type: 'design', status: 'active' }, '# Design B\n'),
      'design/TEMPLATE.md': DESIGN_TEMPLATE,
    })
    const service = new DocService(repoRoot, docsDir, { ...config, entry: [...config.entry, 'design'] })

    const { idPrefix, template } = service.newDocument('design')
    expect(idPrefix).toBe('design-00003-')

    const id = 'design-00003-mine'
    const drafted = template
      .replace('id: design-00001-example-slug', `id: ${id}`)
      .replace('status: draft|active|archived', 'status: draft')
    await service.create(id, drafted)

    const written = onDisk(docsDir, `design/${id}.md`)
    expect(written).toContain('status: draft')
    expect(written).toContain('# Design: <subject>')
  })

  it('allocates the first number for a type with no documents yet', () => {
    const { service } = serviceOn({ 'idea/a.md': IDEA })
    expect(service.newDocument('prd').idPrefix).toBe('prd-00001-')
  })

  // A folder without a TEMPLATE.md is a repo missing that convention, not a
  // reason to refuse: the allocated id is what the board owes the editor.
  it('hands back an empty prefill when the type has no template', () => {
    const { service } = serviceOn({ 'idea/a.md': IDEA })
    expect(service.newDocument('idea').template).toBe('')
  })

  // rule-00001-AC-27.1 and spec-00001-AC-53.2
  it('refuses to prefill a type that is not a flow entry', () => {
    const { service } = serviceOn({ 'idea/a.md': IDEA })
    expect(() => service.newDocument('spec')).toThrowError(/not a flow entry type/)
  })

  // spec-00001-AC-53.1 with rule-00001-AC-26.1
  it('creates the file at the allocated id and commits it as a create', async () => {
    const { docsDir, repoRoot, service } = serviceOn({ 'idea/a.md': IDEA })
    const content = newIdea('idea-00002-a-second-idea')

    const result = await service.create('idea-00002-a-second-idea', content)

    expect(result.committed).toBe(true)
    expect(onDisk(docsDir, 'idea/idea-00002-a-second-idea.md')).toBe(content)
    expect(lastCommitMessage(repoRoot)).toBe('wb(create): idea-00002-a-second-idea')
    expect(lastCommitFiles(repoRoot)).toEqual(['docs/idea/idea-00002-a-second-idea.md'])
  })

  it('creates the folder of a type that has none yet', async () => {
    const { docsDir, service } = serviceOn({ 'idea/a.md': IDEA })
    const content = doc({ id: 'prd-00001-first', type: 'prd', status: 'draft' }, '# First\n')

    await service.create('prd-00001-first', content)

    expect(onDisk(docsDir, 'prd/prd-00001-first.md')).toBe(content)
  })

  // spec-00001-AC-53.2 and rule-00001-AC-27.1 over the write path
  it('refuses a type that is not a flow entry, writing nothing', async () => {
    const { docsDir, repoRoot, service } = serviceOn({ 'idea/a.md': IDEA })
    const before = commitCount(repoRoot)

    await expect(
      service.create('spec-00001-mine', doc({ id: 'spec-00001-mine', type: 'spec', status: 'draft' })),
    ).rejects.toThrowError(/not a flow entry type/)
    expect(existsSync(join(docsDir, 'spec/spec-00001-mine.md'))).toBe(false)
    expect(commitCount(repoRoot)).toBe(before)
  })

  // spec-00001-AC-53.3 — an id already taken is a conflict, never an overwrite
  it('refuses an id a document already holds, leaving the disk alone', async () => {
    const { docsDir, service } = serviceOn({ 'idea/a.md': IDEA })

    await expect(service.create('idea-00001-x', newIdea('idea-00001-x'))).rejects.toThrowError(/already exists/)
    expect(onDisk(docsDir, 'idea/a.md')).toBe(IDEA)
  })

  it('refuses an id whose file is there under a name the graph does not know', async () => {
    const { docsDir, service } = serviceOn({ 'idea/a.md': IDEA })
    writeFileSync(join(docsDir, 'idea/idea-00002-taken.md'), 'not a document at all\n')

    await expect(service.create('idea-00002-taken', newIdea('idea-00002-taken'))).rejects.toThrowError(
      /already exists/,
    )
    expect(onDisk(docsDir, 'idea/idea-00002-taken.md')).toBe('not a document at all\n')
  })

  // spec-00001-AC-53.4 — the slug is the user's, but only in the shape BR-18 fixes
  it('refuses a slug that is not lower case and hyphenated', async () => {
    const { service } = serviceOn({ 'idea/a.md': IDEA })

    for (const id of ['idea-00002-My Idea', 'idea-00002-MyIdea', 'idea-00002-my_idea', 'idea-2-my-idea']) {
      await expect(service.create(id, newIdea(id))).rejects.toThrowError(/is not <type>-<nnnnn>-<slug>/)
    }
  })

  // rule-00001-BR-18: the number is the board's to allocate, not the caller's
  it('refuses a number that is not the allocated one', async () => {
    const { service } = serviceOn({ 'idea/a.md': IDEA })

    await expect(service.create('idea-00009-far-ahead', newIdea('idea-00009-far-ahead'))).rejects.toThrowError(
      /is not the id allocated for a new idea; it is idea-00002-/,
    )
  })

  // The file is filed under the id it was asked for, so its front matter has to
  // agree — a document whose id is not its name is anomalous the moment it lands.
  it('refuses content whose front matter declares another id, or none', async () => {
    const { service } = serviceOn({ 'idea/a.md': IDEA })

    for (const content of [newIdea('idea-00002-something-else'), '# No front matter at all\n']) {
      await expect(service.create('idea-00002-mine', content)).rejects.toThrowError(/does not declare id/)
    }
  })
})

/**
 * The payload behind the global coverage view (spec-00002-FR-10 and FR-11): one
 * row per spec and rule, three counts, and every item with the state the counts
 * were taken from. What is listed is judged by `declaresItems` and `duplicateOf`
 * alone — never by `ok`, never by status (design-00001 §2).
 */
describe('coverage', () => {
  /** Five items in one spec, one AC each but the last, so all three states appear. */
  const SPEC_A = doc(
    { id: 'spec-00001-a', type: 'spec', status: 'active' },
    [
      '# Spec A',
      '',
      '- **spec-00001-FR-1** (Event) the first thing',
      '- **spec-00001-FR-2** (Event) the second thing',
      '- **spec-00001-FR-3** (Event) the third thing',
      '- **spec-00001-FR-4** (Event) the fourth thing',
      '- **spec-00001-FR-5** (Event) the fifth thing',
      '',
      '- **spec-00001-AC-1.1** (spec-00001-FR-1)',
      '  Given a board When it loads Then it works',
      '- **spec-00001-AC-2.1** (spec-00001-FR-2)',
      '  Given a board When it reloads Then it works',
      '- **spec-00001-AC-3.1** (spec-00001-FR-3)',
      '  Given a board When it is read Then it works',
      '- **spec-00001-AC-5.1** (spec-00001-FR-5)',
      '  Given a board When it is closed Then it works',
      '',
    ].join('\n'),
  )
  const SPEC_B = doc(
    { id: 'spec-00002-b', type: 'spec', status: 'draft' },
    [
      '# Spec B',
      '',
      '- **spec-00002-FR-1** (Event) the only thing',
      '',
      '- **spec-00002-AC-1.1** (spec-00002-FR-1)',
      '  Given a board When it loads Then it works',
      '',
    ].join('\n'),
  )
  const RULE = doc(
    { id: 'rule-00001-r', type: 'rule', status: 'active' },
    [
      '# Rule',
      '',
      '- **rule-00001-BR-1** (Constraint) the only rule',
      '',
      '- **rule-00001-AC-1.1** (rule-00001-BR-1)',
      '  Given a rule When it applies Then it holds',
      '',
    ].join('\n'),
  )

  /** A record's acceptance checklist; its own status is no part of the reading. */
  function record(rows: [string, string][]): string {
    return doc(
      { id: 'record-00001-r', type: 'record', status: 'active' },
      [
        '# 验收记录',
        '',
        '| GWT id | 测试 | 结果 |',
        '| --- | --- | --- |',
        ...rows.map(([target, result]) => `| ${target} | some.test.ts | ${result} |`),
        '',
      ].join('\n'),
    )
  }

  const EVIDENCE = record([
    ['spec-00001-AC-1.1', 'pass'],
    ['spec-00001-AC-2.1', 'fail'],
    ['spec-00001-AC-5.1', 'pass'],
    ['rule-00001-AC-1.1', 'pass'],
  ])
  const TREE = { 'spec/a.md': SPEC_A, 'spec/b.md': SPEC_B, 'rule/r.md': RULE, 'record/r.md': EVIDENCE }

  const rowOf = (service: DocService, docId: string) => service.coverage().find((row) => row.docId === docId)!

  // spec-00002-AC-10.1
  it('lists every spec and rule, each with its three counts', () => {
    const { service } = serviceOn(TREE)

    const rows = service.coverage()

    expect(rows.map((row) => [row.docId, row.verified, row.failing, row.uncovered])).toEqual([
      ['rule-00001-r', 1, 0, 0],
      ['spec-00001-a', 2, 1, 2],
      ['spec-00002-b', 0, 0, 1],
    ])
    expect(rows.map((row) => row.title)).toEqual(['Rule', 'Spec A', 'Spec B'])
  })

  // spec-00002-AC-10.2 — two of the five items are uncovered
  it('counts the uncovered items of a document', () => {
    const { service } = serviceOn(TREE)

    expect(rowOf(service, 'spec-00001-a').uncovered).toBe(2)
  })

  // spec-00002-FR-11's data half: the row carries every item with its state
  it('carries each item id and its coverage on the row', () => {
    const { service } = serviceOn(TREE)

    expect(rowOf(service, 'spec-00001-a').items).toEqual([
      { id: 'spec-00001-FR-1', coverage: 'verified' },
      { id: 'spec-00001-FR-2', coverage: 'failing' },
      { id: 'spec-00001-FR-3', coverage: 'uncovered' },
      { id: 'spec-00001-FR-4', coverage: 'uncovered' },
      { id: 'spec-00001-FR-5', coverage: 'verified' },
    ])
  })

  // spec-00002-AC-10.3
  it('lists nothing when the repo holds no spec and no rule', () => {
    const { service } = serviceOn({ 'prd/a.md': DRAFT_PRD, 'record/r.md': EVIDENCE })

    expect(service.coverage()).toEqual([])
  })

  // spec-00002-AC-10.4 — a record gains a passing row outside the board
  it('re-derives the counts once the tree it read has been invalidated', () => {
    const { docsDir, service } = serviceOn(TREE)
    expect(rowOf(service, 'spec-00002-b').uncovered).toBe(1)

    writeFileSync(join(docsDir, 'record/r.md'), record([['spec-00002-AC-1.1', 'pass']]))
    service.invalidate()

    expect(rowOf(service, 'spec-00002-b')).toMatchObject({ verified: 1, uncovered: 0 })
  })

  // spec-00002-AC-10.9 — status is no part of the selection, on either side
  it('lists an archived spec and a draft rule alike', () => {
    const { service } = serviceOn({
      'spec/a.md': SPEC_A.replace('status: active', 'status: archived'),
      'rule/r.md': RULE.replace('status: active', 'status: draft'),
      'record/r.md': EVIDENCE,
    })

    expect(service.coverage().map((row) => row.docId)).toEqual(['rule-00001-r', 'spec-00001-a'])
  })

  // spec-00002-AC-10.10 — broken front matter, readable body
  it('lists a document whose front matter is broken but whose body parses', () => {
    const { service } = serviceOn({
      'spec/a.md': SPEC_A.replace('status: active', 'status: nonsense'),
      'record/r.md': EVIDENCE,
    })

    expect(service.graph().nodes.find((node) => node.id === 'spec-00001-a')!.ok).toBe(false)
    expect(rowOf(service, 'spec-00001-a')).toMatchObject({ verified: 2, failing: 1, uncovered: 2 })
  })

  /**
   * spec-00002-AC-8.7 verified where the AC puts it — on the coverage payload
   * itself. plan-00012 could only show that the items of a colliding document
   * are claimed by nobody; the row is what the user sees, and it must be absent.
   */
  it('leaves a document colliding on its id out of the payload', () => {
    const { service } = serviceOn({
      'spec/a.md': SPEC_A,
      'spec/clash.md': doc({ id: 'spec-00001-a', type: 'spec', status: 'draft' }, '# The other Spec A\n'),
      'rule/r.md': RULE,
      'record/r.md': EVIDENCE,
    })

    expect(service.graph().nodes.map((node) => node.id)).toContain('spec/clash.md')
    expect(service.coverage().map((row) => row.docId)).toEqual(['rule-00001-r'])
  })

  /**
   * The body-parse cache of spec-00002 §7: the heaviest read the board has must
   * not walk the tree again while nothing has changed. Observed the only honest
   * way — a body edited behind the cache's back is still answered from the parse
   * that came before it, and the one `invalidate()` the graph uses lets it
   * through (design-00001 §2).
   */
  it('answers a repeated read from the bodies it already parsed', () => {
    const { docsDir, service } = serviceOn(TREE)
    expect(rowOf(service, 'spec-00002-b').uncovered).toBe(1)

    writeFileSync(join(docsDir, 'record/r.md'), record([['spec-00002-AC-1.1', 'pass']]))

    expect(rowOf(service, 'spec-00002-b').uncovered).toBe(1)
    service.invalidate()
    expect(rowOf(service, 'spec-00002-b').uncovered).toBe(0)
  })

  // The cache is shared, so the two readings are the same reading (design-00001 §7)
  it('gives the row the very coverage /items gives the same document', () => {
    const { service } = serviceOn(TREE)

    expect(rowOf(service, 'spec-00001-a').items).toEqual(
      service.items('spec-00001-a').items.map((item) => ({ id: item.id, coverage: item.coverage })),
    )
  })
})

/**
 * The parse cache (spec-00001 §7 非功能项, decision-00008 §2 第 8 条). A repeated
 * read must not walk the tree again, and the only honest way to observe that from
 * outside is a change the cache has not been told about: it is still answered
 * from the parse that came before it. Both invalidation signals are here — the
 * write path's own, and the explicit one the watcher pulls (server.ts).
 */
describe('the parse cache', () => {
  const secondPrd = doc({ id: 'prd-00002-y', type: 'prd', status: 'draft' }, '# Y\n')

  it('answers a repeated read from the parse it already has', () => {
    const { docsDir, service } = serviceOn({ 'prd/a.md': DRAFT_PRD })
    expect(service.graph().nodes).toHaveLength(1)

    writeFileSync(join(docsDir, 'prd/b.md'), secondPrd)

    // Nothing has invalidated it, so the tree was not read again.
    expect(service.graph().nodes).toHaveLength(1)
    service.invalidate()
    expect(service.graph().nodes).toHaveLength(2)
  })

  it('drops the cache when a save writes through it', async () => {
    const { service } = serviceOn({ 'prd/a.md': DRAFT_PRD })
    const base = service.read('prd-00001-x')

    await service.save('prd-00001-x', DRAFT_PRD.replace('# X', '# X renamed'), base.hash)

    expect(service.graph().nodes[0]!.title).toBe('X renamed')
  })

  it('drops the cache when a status change writes through it', async () => {
    const { service } = serviceOn({ 'prd/a.md': DRAFT_PRD })

    await service.changeStatus('prd-00001-x', 'active')

    expect(service.graph().nodes[0]!.status).toBe('active')
  })

  it('drops the cache when a create writes through it', async () => {
    const { service } = serviceOn({ 'prd/a.md': DRAFT_PRD })

    await service.create('prd-00002-y', secondPrd)

    expect(service.graph().nodes.map((node) => node.id)).toEqual(['prd-00001-x', 'prd-00002-y'])
  })
})

/**
 * Addressing a document by an id two of them declare (spec-00002-FR-9). The id
 * points at no single document, so a write addressed by it is refused as a
 * conflict — and the way out is the editor, which addresses the node's own file
 * path.
 */
describe('a write addressed by a colliding id', () => {
  const FIRST = doc({ id: 'spec-00002-clash', type: 'spec', status: 'draft' }, '# The first\n')
  const SECOND = doc({ id: 'spec-00002-clash', type: 'spec', status: 'draft' }, '# The second\n')

  function colliding() {
    return serviceOn({ 'spec/first.md': FIRST, 'spec/second.md': SECOND })
  }

  // spec-00002-AC-9.2
  it('refuses it, names the files to fix, and writes nothing', async () => {
    const { docsDir, repoRoot, service } = colliding()
    const before = commitCount(repoRoot)

    await expect(service.changeStatus('spec-00002-clash', 'active')).rejects.toThrowError(ConflictError)
    await expect(service.changeStatus('spec-00002-clash', 'active')).rejects.toThrowError(
      /spec\/first\.md and spec\/second\.md; fix the id collision first/,
    )
    expect(onDisk(docsDir, 'spec/first.md')).toBe(FIRST)
    expect(onDisk(docsDir, 'spec/second.md')).toBe(SECOND)
    expect(commitCount(repoRoot)).toBe(before)
  })

  it('refuses every other write addressed the same way', async () => {
    const { service } = colliding()
    const collision = /fix the id collision first/

    expect(() => service.read('spec-00002-clash')).toThrowError(collision)
    await expect(service.save('spec-00002-clash', 'x', 'h')).rejects.toThrowError(collision)
    await expect(service.review('spec-00002-clash', { action: 'accept' })).rejects.toThrowError(collision)
  })

  // spec-00002-AC-9.3 — the refusal keeps no state, so it repeats unchanged
  it('refuses the same request again, still writing nothing', async () => {
    const { docsDir, repoRoot, service } = colliding()
    const before = commitCount(repoRoot)

    await expect(service.changeStatus('spec-00002-clash', 'active')).rejects.toThrowError(ConflictError)
    await expect(service.changeStatus('spec-00002-clash', 'active')).rejects.toThrowError(ConflictError)
    expect(onDisk(docsDir, 'spec/first.md')).toBe(FIRST)
    expect(onDisk(docsDir, 'spec/second.md')).toBe(SECOND)
    expect(commitCount(repoRoot)).toBe(before)
  })

  // spec-00002-AC-9.4 — this is the repair path: edit by path, change the id, save
  it('saves an edit addressed by the node path, writing that one file only', async () => {
    const { docsDir, repoRoot, service } = colliding()
    const base = service.read('spec/second.md')
    const fixed = SECOND.replace('spec-00002-clash', 'spec-00003-apart')

    const result = await service.save('spec/second.md', fixed, base.hash)

    expect(result.committed).toBe(true)
    expect(onDisk(docsDir, 'spec/second.md')).toBe(fixed)
    expect(onDisk(docsDir, 'spec/first.md')).toBe(FIRST)
    expect(lastCommitFiles(repoRoot)).toEqual(['docs/spec/second.md'])
    // The collision is gone, so both are sound documents again.
    expect(service.graph().nodes.map((node) => node.id)).toEqual(['spec-00002-clash', 'spec-00003-apart'])
  })

  // spec-00002-AC-8.8 — a scope landing on a colliding document is an unresolved gap
  it('refuses to resolve a plan whose scope names an item of a colliding document', async () => {
    const spec = doc(
      { id: 'spec-00001-b', type: 'spec', status: 'active' },
      [
        '# Spec',
        '',
        '- **spec-00001-FR-1** (Event) the system shall do the thing',
        '',
        '- **spec-00001-AC-1.1** (spec-00001-FR-1)',
        '  Given a board',
        '  When it loads',
        '  Then it works',
        '',
      ].join('\n'),
    )
    const { docsDir, service } = serviceOn({
      'spec/b.md': spec,
      'spec/clash.md': doc({ id: 'spec-00001-b', type: 'spec', status: 'draft' }, '# The other one\n'),
      'plan/a.md': doc(
        { id: 'plan-00001-y', type: 'plan', status: 'open', implements: '[spec-00001-FR-1]' },
        '# Plan\n',
      ),
      'record/r.md': doc(
        { id: 'record-00001-r', type: 'record', status: 'active', parent: 'plan-00001-y' },
        ['# 验收记录', '', '| GWT id | 测试 | 结果 |', '| --- | --- | --- |', '| spec-00001-AC-1.1 | some.test.ts | pass |', ''].join('\n'),
      ),
    })

    const refusal = await service.changeStatus('plan-00001-y', 'resolved').catch((error) => error)

    expect(refusal).toBeInstanceOf(GateError)
    expect(refusal.gaps).toEqual(['spec-00001-FR-1'])
    expect(onDisk(docsDir, 'plan/a.md')).toContain('status: open')
  })

  /**
   * rule-00001-BR-18 on the create path: the check asks about declared ids, not
   * node keys. `findNode` alone would miss a colliding document and file a third
   * one under the same id (design-00001 §2).
   */
  it('refuses to create a third document under an id two already collide on', async () => {
    const { docsDir, service } = serviceOn({
      'idea/first.md': doc({ id: 'idea-00001-clash', type: 'idea', status: 'draft' }, '# First\n'),
      'idea/second.md': doc({ id: 'idea-00001-clash', type: 'idea', status: 'draft' }, '# Second\n'),
    })

    await expect(
      service.create('idea-00001-clash', doc({ id: 'idea-00001-clash', type: 'idea', status: 'draft' }, '# Third\n')),
    ).rejects.toThrowError(ConflictError)
    expect(existsSync(join(docsDir, 'idea/idea-00001-clash.md'))).toBe(false)
  })

  // The collided number stays allocated, so the next create takes the one after it
  it('allocates the number after the collided one', () => {
    const { service } = serviceOn({
      'idea/first.md': doc({ id: 'idea-00003-clash', type: 'idea', status: 'draft' }, '# First\n'),
      'idea/second.md': doc({ id: 'idea-00003-clash', type: 'idea', status: 'draft' }, '# Second\n'),
    })

    expect(service.newDocument('idea').idPrefix).toBe('idea-00004-')
  })
})

/**
 * The fifth session kind's own reception (spec-00006-FR-1, FR-2 and FR-9): the
 * ruling of rule-00001-BR-29, the instruction built from the target as it stands,
 * and the create form that files its own target.
 */
describe('cowritePlan', () => {
  const DRAFT_REPORT = doc({ id: 'report-00001-r', type: 'report', status: 'draft' }, '# Report\n')
  const OPEN_ISSUE = doc({ id: 'issue-00001-i', type: 'issue', status: 'open' }, '# Issue\n')

  function cowriteServiceOn(files: Record<string, string>) {
    const { repoRoot, docsDir } = makeRepo(files)
    return { repoRoot, docsDir, service: new DocService(repoRoot, docsDir, cowriteConfig()) }
  }

  // rule-00001-AC-29.1
  it('builds a plan for a draft report, carrying the target as it stands', () => {
    const { service } = cowriteServiceOn({ 'report/r.md': DRAFT_REPORT })

    const plan = service.cowritePlan('report-00001-r')

    expect(plan.kind).toBe('cowrite')
    expect(plan.sourceId).toBe('report-00001-r')
    expect(plan.cowrite).toEqual({ targetPath: 'report/r.md', preId: 'report-00001-r', preStatus: 'draft' })
    expect(plan.instruction).toContain('report/README.md')
  })

  // rule-00001-AC-29.2
  it('builds a plan for an open work item, and remembers that status as the one to keep', () => {
    const { service } = cowriteServiceOn({ 'issue/i.md': OPEN_ISSUE })

    expect(service.cowritePlan('issue-00001-i').cowrite!.preStatus).toBe('open')
  })

  // spec-00006-AC-9.1 — the entry is offered whatever the status is; the refusal is here
  // rule-00001-AC-29.3
  it('refuses an active living document and says why', () => {
    const { service } = cowriteServiceOn({
      'design/d.md': doc({ id: 'design-00001-d', type: 'design', status: 'active' }, '# Design\n'),
    })

    expect(() => service.cowritePlan('design-00001-d')).toThrowError(/rule-00001-BR-29/)
    expect(() => service.cowritePlan('design-00001-d')).toThrowError(/is active/)
  })

  // spec-00006-AC-9.2
  // rule-00001-AC-29.4
  it('refuses a resolved work item', () => {
    const { service } = cowriteServiceOn({
      'task/t.md': doc({ id: 'task-00001-t', type: 'task', status: 'resolved' }, '# Task\n'),
    })

    expect(() => service.cowritePlan('task-00001-t')).toThrowError(WorkflowError)
  })

  it('refuses an anomalous document', () => {
    const { service } = cowriteServiceOn({ 'report/broken.md': doc({ id: 'nope', type: 'report', status: 'draft' }) })

    expect(() => service.cowritePlan('nope')).toThrowError(/front matter problems/)
  })

  // spec-00001-AC-19.3 — the deleted target refuses the fifth kind of start too
  it('refuses with a doc-missing conflict when the target is gone from disk', () => {
    const { docsDir, service } = cowriteServiceOn({ 'report/r.md': DRAFT_REPORT })
    service.graph()
    rmSync(join(docsDir, 'report/r.md'))

    const refusal = (() => {
      try {
        service.cowritePlan('report-00001-r')
      } catch (error) {
        return error as ConflictError
      }
      throw new Error('the start was admitted')
    })()

    expect(refusal).toBeInstanceOf(ConflictError)
    expect(refusal.reason).toBe('doc-missing')
  })

  // The numbering the instruction offers counts the running sessions' reservations
  // as taken (spec-00003-FR-1's reading of rule-00001-BR-18).
  it('starts the reference numbering above the existing and the reserved numbers', () => {
    const { service } = cowriteServiceOn({
      'report/r.md': DRAFT_REPORT,
      'reference/a.md': doc({ id: 'reference-00002-a', type: 'reference', status: 'draft' }, '# A\n'),
    })

    expect(service.cowritePlan('report-00001-r').instruction).toContain('reference-00003-')
    expect(service.cowritePlan('report-00001-r', undefined, [7]).instruction).toContain('reference-00008-')
  })

  it('carries the materials it was given into the instruction', () => {
    const { service } = cowriteServiceOn({ 'report/r.md': DRAFT_REPORT, 'prd/a.md': DRAFT_PRD })

    const plan = service.cowritePlan('report-00001-r', { text: 'pasted', docIds: ['prd-00001-x'] })

    expect(plan.instruction).toContain('pasted')
    expect(plan.instruction).toContain('prd-00001-x at prd/a.md')
  })
})

/**
 * The create form of spec-00006-FR-2: the three rejections of spec-00001-FR-53
 * judged before anything is written, then the document filed from its template and
 * committed — no editor save in the middle (design-00001 §11.2).
 */
describe('the cowrite create form', () => {
  const TEMPLATE = '---\nid: idea-00000-slug\ntype: idea\nstatus: draft\nparent:\n---\n\n# Title\n\n## 1. Context\n'

  function createServiceOn(files: Record<string, string> = {}) {
    const { repoRoot, docsDir } = makeRepo({ 'idea/TEMPLATE.md': TEMPLATE, ...files })
    return { repoRoot, docsDir, service: new DocService(repoRoot, docsDir, cowriteConfig()) }
  }

  // spec-00006-AC-2.1 — the plan half: nothing is on disk when the plan is built
  it('plans the document it will file, without writing anything', () => {
    const { docsDir, service } = createServiceOn()

    const planned = service.cowriteCreatePlan('idea', 'co-written')

    expect(planned.docId).toBe('idea-00001-co-written')
    expect(planned.plan.cowrite).toEqual({
      targetPath: 'idea/idea-00001-co-written.md',
      preId: 'idea-00001-co-written',
      preStatus: 'draft',
    })
    expect(existsSync(join(docsDir, 'idea/idea-00001-co-written.md'))).toBe(false)
  })

  // spec-00006-AC-2.1 — the file half: the type's template, prefilled, in one create commit
  it('files the document from the template and commits it as a create', async () => {
    const { repoRoot, docsDir, service } = createServiceOn()
    const planned = service.cowriteCreatePlan('idea', 'co-written')

    const commit = await service.createForCowrite({ id: planned.docId, path: planned.path, type: 'idea' })

    expect(planned.path).toBe('idea/idea-00001-co-written.md')
    expect(commit.committed).toBe(true)
    expect(onDisk(docsDir, 'idea/idea-00001-co-written.md')).toContain('id: idea-00001-co-written')
    expect(onDisk(docsDir, 'idea/idea-00001-co-written.md')).toContain('status: draft')
    expect(onDisk(docsDir, 'idea/idea-00001-co-written.md')).toContain('## 1. Context')
    expect(lastCommitMessage(repoRoot)).toBe('wb(create): idea-00001-co-written')
  })

  // spec-00006-AC-2.2 — the rejections are the plan's, and the plan is what the
  // filing is threaded from, so a refusal here never reaches a write
  it('refuses a slug that is not lower-case hyphenated, and files nothing', () => {
    const { docsDir, service } = createServiceOn()

    expect(() => service.cowriteCreatePlan('idea', 'Co Written')).toThrowError(WorkflowError)
    expect(existsSync(join(docsDir, 'idea'))).toBe(true)
    expect(readFileSync(join(docsDir, 'idea/TEMPLATE.md'), 'utf8')).toBe(TEMPLATE)
  })

  // spec-00006-AC-2.4
  it('refuses a type that is not a flow entry type', () => {
    const { service } = createServiceOn()

    expect(() => service.cowriteCreatePlan('spec', 'co-written')).toThrowError(/not a flow entry type/)
  })

  // spec-00006-AC-2.3 — a file the graph could not read as a document still holds its id
  it('refuses an id that is already taken on disk', () => {
    const { service } = createServiceOn({ 'idea/idea-00001-taken.md': '# no front matter at all\n' })

    expect(() => service.cowriteCreatePlan('idea', 'taken')).toThrowError(ConflictError)
  })

  /**
   * The one thing the prefill can get wrong: a template whose `id` line the fill
   * did not reach leaves a document whose front matter disagrees with its own file
   * name, which is anomalous the moment it lands (spec-00001-FR-2). It is asserted
   * rather than trusted, and the half-written file goes back off the disk.
   */
  it('refuses a prefilled template that does not declare the id, and files nothing', async () => {
    // A template whose front matter will not parse: the id line is filled in the
    // right place and still declares nothing, because the block around it is
    // broken YAML.
    const { docsDir, service } = createServiceOn({
      'prd/TEMPLATE.md': '---\nid: prd-00000-slug\ntype: prd\nparent: [unclosed\n---\n\n# Title\n',
    })
    const planned = service.cowriteCreatePlan('prd', 'from-broken')

    await expect(
      service.createForCowrite({ id: planned.docId, path: planned.path, type: 'prd' }),
    ).rejects.toThrowError(/does not declare id: prd-00001-from-broken/)
    expect(existsSync(join(docsDir, 'prd/prd-00001-from-broken.md'))).toBe(false)
  })

  /**
   * The number is allocated once, by the plan, and threaded into the filing: the
   * document the session was admitted on is the document that lands
   * (design-00001 §11.2).
   */
  it('files the very id the plan allocated', async () => {
    const { docsDir, service } = createServiceOn({
      'idea/idea-00001-first.md': doc({ id: 'idea-00001-first', type: 'idea', status: 'draft' }),
    })
    const planned = service.cowriteCreatePlan('idea', 'second')

    await service.createForCowrite({ id: planned.docId, path: planned.path, type: 'idea' })

    expect(planned.docId).toBe('idea-00002-second')
    expect(existsSync(join(docsDir, 'idea/idea-00002-second.md'))).toBe(true)
  })

  /**
   * A `reference` target has itself taken a reference number, and nothing is on
   * disk when the instruction is built: the numbering it offers has to start
   * **past** the target's own, or the session's first document would collide with
   * the document it is writing (rule-00001-BR-18).
   */
  it('starts the reference numbering past a reference target’s own number', () => {
    const config = cowriteConfig()
    config.entry.push('reference')
    const { repoRoot, docsDir } = makeRepo({
      'reference/reference-00001-old.md': doc({ id: 'reference-00001-old', type: 'reference', status: 'draft' }),
    })
    const service = new DocService(repoRoot, docsDir, config)

    const planned = service.cowriteCreatePlan('reference', 'target')

    expect(planned.docId).toBe('reference-00002-target')
    expect(planned.plan.instruction).toContain('reference-00003-')
  })

  // A folder with no template is a repo missing that convention, not a reason to
  // refuse: the document still has to carry its three front matter lines.
  it('files a document with the minimal front matter when the type has no template', async () => {
    const { repoRoot, docsDir } = makeRepo({})
    const service = new DocService(repoRoot, docsDir, cowriteConfig())
    const planned = service.cowriteCreatePlan('prd', 'from-nothing')

    await service.createForCowrite({ id: planned.docId, path: planned.path, type: 'prd' })

    expect(onDisk(docsDir, 'prd/prd-00001-from-nothing.md')).toBe(
      '---\nid: prd-00001-from-nothing\ntype: prd\nstatus: draft\n---\n\n',
    )
  })
})

/**
 * The status lock and the editor bypass (spec-00006-FR-10, design-00001 §11.4):
 * while a cowrite session runs on a document, nothing promotes it and nothing
 * rewrites its identity behind the session's back — the body is another matter,
 * and that is the turn-taking the round is about.
 */
describe('the cowrite status lock', () => {
  function lockedServiceOn(files: Record<string, string>, busy: string[]) {
    const { repoRoot, docsDir } = makeRepo(files)
    const service = new DocService(repoRoot, docsDir, cowriteConfig())
    // The probe answers with the front matter the session was admitted on, which
    // is what a save's identity is held to (design-00001 §11.4).
    service.attachCowriteProbe((docId) =>
      busy.includes(docId) ? { preId: docId, preStatus: 'draft' } : undefined,
    )
    return { repoRoot, docsDir, service }
  }

  // spec-00006-AC-10.1
  it('refuses a status change and an accept with doc-busy while the session runs', async () => {
    const { docsDir, service } = lockedServiceOn({ 'prd/a.md': DRAFT_PRD }, ['prd-00001-x'])

    for (const action of [service.changeStatus('prd-00001-x', 'active'), service.review('prd-00001-x', { action: 'accept' })]) {
      const refusal = await action.catch((error) => error)
      expect(refusal).toBeInstanceOf(SessionBusyError)
      expect(refusal.reason).toBe('doc-busy')
      expect(refusal.message).toMatch(/running cowrite session/)
    }
    expect(onDisk(docsDir, 'prd/a.md')).toBe(DRAFT_PRD)
  })

  // spec-00006-AC-10.2 — the lock is the session, so it lifts when the session ends
  it('evaluates the review gates as usual once no session is running on the document', async () => {
    const busy: string[] = ['prd-00001-x']
    const { docsDir, service } = lockedServiceOn({ 'prd/a.md': DRAFT_PRD }, busy)
    busy.length = 0

    expect(await service.review('prd-00001-x', { action: 'accept' })).toMatchObject({ status: 'active' })
    expect(onDisk(docsDir, 'prd/a.md')).toContain('status: active')
  })

  // spec-00006-AC-10.3 — a whole-file overwrite is how a save could move the status
  it('refuses a save that moves the front matter status or id', async () => {
    const { docsDir, service } = lockedServiceOn({ 'prd/a.md': DRAFT_PRD }, ['prd-00001-x'])
    const base = service.read('prd-00001-x')

    for (const content of [
      DRAFT_PRD.replace('status: draft', 'status: active'),
      DRAFT_PRD.replace('id: prd-00001-x', 'id: prd-00001-renamed'),
    ]) {
      const refusal = await service.save('prd-00001-x', content, base.hash).catch((error) => error)
      expect(refusal).toBeInstanceOf(SessionBusyError)
      expect(refusal.reason).toBe('doc-busy')
    }
    expect(onDisk(docsDir, 'prd/a.md')).toBe(DRAFT_PRD)
  })

  /**
   * spec-00006-AC-10.3, the moving-reference case: the agent has already written
   * `status: active` into the file and the clean buffer reloaded it, so a
   * body-only save now carries that status. Judged against the **disk** it would
   * pass and land the promotion nobody made; judged against the two values the
   * session was admitted on it is the identity move it is.
   */
  // spec-00006-AC-10.3
  it('refuses a save carrying a status the agent moved, however the disk reads now', async () => {
    const MOVED = `${DRAFT_PRD.replace('status: draft', 'status: active')}the agent wrote this\n`
    const { docsDir, service } = lockedServiceOn({ 'prd/a.md': MOVED }, ['prd-00001-x'])
    const base = service.read('prd-00001-x')

    const refusal = await service.save('prd-00001-x', `${MOVED}and the owner this\n`, base.hash).catch((e) => e)

    expect(refusal).toBeInstanceOf(SessionBusyError)
    expect(refusal.reason).toBe('doc-busy')
    expect(onDisk(docsDir, 'prd/a.md')).toBe(MOVED)
  })

  // spec-00006-AC-10.4 — the body is the round's turn-taking, and it commits as an edit
  it('lets a body-only save through and commits it as an edit', async () => {
    const { repoRoot, docsDir, service } = lockedServiceOn({ 'prd/a.md': DRAFT_PRD }, ['prd-00001-x'])
    const base = service.read('prd-00001-x')

    const result = await service.save('prd-00001-x', `${DRAFT_PRD}the owner typed this\n`, base.hash)

    expect(result.committed).toBe(true)
    expect(lastCommitMessage(repoRoot)).toBe('wb(edit): prd-00001-x')
    expect(onDisk(docsDir, 'prd/a.md')).toContain('the owner typed this')
  })

  it('leaves every write path alone when no probe was ever attached', async () => {
    const { service } = serviceOn({ 'prd/a.md': DRAFT_PRD })

    expect(await service.changeStatus('prd-00001-x', 'active')).toMatchObject({ status: 'active' })
  })
})

/**
 * The collapse filter (spec-00006-FR-6 and FR-8, rule-00001-BR-30's enforcement
 * layer, design-00001 §11.3): of everything that moved under docs/ since the
 * session's own snapshot, the target document and the well-formed new references
 * are committed, and everything else is put back — bar the two exemptions.
 */
describe('commitCowriteChanges', () => {
  const DRAFT_IDEA = doc({ id: 'idea-00001-y', type: 'idea', status: 'draft' }, '# Y\n\nas committed\n')
  const REFERENCE = (id: string) => doc({ id, type: 'reference', status: 'draft' }, `# ${id}\n`)

  function cowriteServiceOn(files: Record<string, string>, git?: GitLayer) {
    const { repoRoot, docsDir } = makeRepo(files)
    return { repoRoot, docsDir, service: new DocService(repoRoot, docsDir, cowriteConfig(), git) }
  }

  /** The two snapshots the registry takes at a cowrite start, and the plan they ride on. */
  function admit(service: DocService, docId: string, targetPath: string, preStatus = 'draft') {
    return {
      before: service.snapshotDocs(),
      plan: {
        kind: 'cowrite',
        sourceId: docId,
        instruction: '',
        cowrite: { targetPath, preId: docId, preStatus },
        contentBaseline: service.contentSnapshotDocs(),
      } satisfies SessionPlan,
    }
  }

  function write(docsDir: string, relPath: string, content: string): void {
    mkdirSync(join(docsDir, relPath, '..'), { recursive: true })
    writeFileSync(join(docsDir, relPath), content)
  }

  /**
   * One still-running session's claim, as `SessionManager.runningClaims` states it
   * (design-00001 §11.3). The baseline defaults to empty dirt — a session that
   * started on a clean tree — which is the ordinary case.
   */
  function claim(fields: Omit<SessionClaim, 'baseline'> & { baseline?: DirtySnapshot }): SessionClaim {
    return { baseline: new Map(), ...fields }
  }

  // spec-00006-AC-6.1
  // rule-00001-AC-30.2
  it('commits the target and restores a rewrite of another existing document', async () => {
    const { repoRoot, docsDir, service } = cowriteServiceOn({ 'prd/a.md': DRAFT_PRD, 'idea/b.md': DRAFT_IDEA })
    const { before, plan } = admit(service, 'prd-00001-x', 'prd/a.md')
    write(docsDir, 'prd/a.md', `${DRAFT_PRD}written together\n`)
    write(docsDir, 'idea/b.md', `${DRAFT_IDEA}out of scope\n`)

    const outcome = await service.commitCowriteChanges(plan, before)

    expect(outcome.committed).toBe(true)
    expect(lastCommitMessage(repoRoot)).toBe('wb(cowrite): prd-00001-x')
    expect(lastCommitFiles(repoRoot)).toEqual(['docs/prd/a.md'])
    expect(onDisk(docsDir, 'idea/b.md')).toBe(DRAFT_IDEA)
  })

  // spec-00006-AC-6.1 for a path that was already dirty: the snapshot's own text
  // is what it goes back to, since HEAD is not what the session inherited
  it('restores an out-of-scope path to the text the session inherited', async () => {
    const { docsDir, service } = cowriteServiceOn({ 'prd/a.md': DRAFT_PRD, 'idea/b.md': DRAFT_IDEA })
    write(docsDir, 'idea/b.md', `${DRAFT_IDEA}dirty before the session\n`)
    const { before, plan } = admit(service, 'prd-00001-x', 'prd/a.md')
    write(docsDir, 'prd/a.md', `${DRAFT_PRD}written together\n`)
    write(docsDir, 'idea/b.md', `${DRAFT_IDEA}the session wrote over it\n`)

    await service.commitCowriteChanges(plan, before)

    expect(onDisk(docsDir, 'idea/b.md')).toBe(`${DRAFT_IDEA}dirty before the session\n`)
  })

  it('deletes again a path the session inherited as deleted', async () => {
    const { docsDir, service } = cowriteServiceOn({ 'prd/a.md': DRAFT_PRD, 'idea/b.md': DRAFT_IDEA })
    rmSync(join(docsDir, 'idea/b.md'))
    const { before, plan } = admit(service, 'prd-00001-x', 'prd/a.md')
    write(docsDir, 'idea/b.md', 'the session brought it back\n')

    await service.commitCowriteChanges(plan, before)

    expect(existsSync(join(docsDir, 'idea/b.md'))).toBe(false)
  })

  it('brings back a document the session deleted', async () => {
    const { docsDir, service } = cowriteServiceOn({ 'prd/a.md': DRAFT_PRD, 'idea/b.md': DRAFT_IDEA })
    const { before, plan } = admit(service, 'prd-00001-x', 'prd/a.md')
    rmSync(join(docsDir, 'idea/b.md'))

    await service.commitCowriteChanges(plan, before)

    expect(onDisk(docsDir, 'idea/b.md')).toBe(DRAFT_IDEA)
  })

  // spec-00006-AC-6.2
  // rule-00001-AC-30.3
  it('deletes a new document of a type other than reference', async () => {
    const { repoRoot, docsDir, service } = cowriteServiceOn({ 'prd/a.md': DRAFT_PRD })
    const { before, plan } = admit(service, 'prd-00001-x', 'prd/a.md')
    write(docsDir, 'prd/a.md', `${DRAFT_PRD}written together\n`)
    write(docsDir, 'spec/invented.md', doc({ id: 'spec-00001-z', type: 'spec', status: 'draft' }, '# Z\n'))

    await service.commitCowriteChanges(plan, before)

    expect(existsSync(join(docsDir, 'spec/invented.md'))).toBe(false)
    expect(lastCommitFiles(repoRoot)).toEqual(['docs/prd/a.md'])
  })

  // spec-00006-AC-6.4
  // rule-00001-AC-30.5
  it('puts the target’s front matter status back and commits the body it wrote', async () => {
    const { repoRoot, docsDir, service } = cowriteServiceOn({ 'prd/a.md': DRAFT_PRD })
    const { before, plan } = admit(service, 'prd-00001-x', 'prd/a.md')
    write(docsDir, 'prd/a.md', `${DRAFT_PRD.replace('status: draft', 'status: active')}written together\n`)

    await service.commitCowriteChanges(plan, before)

    expect(onDisk(docsDir, 'prd/a.md')).toContain('status: draft')
    expect(onDisk(docsDir, 'prd/a.md')).toContain('written together')
    expect(lastCommitFiles(repoRoot)).toEqual(['docs/prd/a.md'])
    expect(git(repoRoot, 'show', 'HEAD:docs/prd/a.md')).toContain('status: draft')
  })

  // rule-00001-AC-28.2 — an open work item is cowritten and stays open
  it('keeps an open work item open, whatever the session wrote into its status line', async () => {
    const OPEN_PLAN = doc({ id: 'plan-00002-o', type: 'plan', status: 'open' }, '# Plan\n')
    const { docsDir, service } = cowriteServiceOn({ 'plan/o.md': OPEN_PLAN })
    const { before, plan } = admit(service, 'plan-00002-o', 'plan/o.md', 'open')
    write(docsDir, 'plan/o.md', `${OPEN_PLAN.replace('status: open', 'status: resolved')}written together\n`)

    await service.commitCowriteChanges(plan, before)

    expect(onDisk(docsDir, 'plan/o.md')).toContain('status: open')
    expect(onDisk(docsDir, 'plan/o.md')).toContain('written together')
  })

  it('puts the target’s front matter id back', async () => {
    const { docsDir, service } = cowriteServiceOn({ 'prd/a.md': DRAFT_PRD })
    const { before, plan } = admit(service, 'prd-00001-x', 'prd/a.md')
    write(docsDir, 'prd/a.md', DRAFT_PRD.replace('id: prd-00001-x', 'id: prd-00002-renamed'))

    await service.commitCowriteChanges(plan, before)

    expect(onDisk(docsDir, 'prd/a.md')).toContain('id: prd-00001-x')
  })

  it('reports a target whose front matter block the session removed, and commits it all the same', async () => {
    const { repoRoot, docsDir, service } = cowriteServiceOn({ 'prd/a.md': DRAFT_PRD })
    const { before, plan } = admit(service, 'prd-00001-x', 'prd/a.md')
    write(docsDir, 'prd/a.md', '# X\n\nno front matter left\n')

    const outcome = await service.commitCowriteChanges(plan, before)

    expect(outcome.problems.some((problem) => /front matter block is gone/.test(problem))).toBe(true)
    expect(outcome.problems.some((problem) => /front matter is missing/.test(problem))).toBe(true)
    expect(lastCommitFiles(repoRoot)).toEqual(['docs/prd/a.md'])
  })

  /**
   * spec-00006-AC-6.5 — another running session's product is left for its own
   * wrap-up. The exemption is that session's **claim**, worked out from the
   * registry: an advance claims what it files under its target type's folder
   * carrying the id prefix its expectation fixed.
   */
  // spec-00006-AC-6.5
  it('neither restores nor stages the product another running advance is writing', async () => {
    const { repoRoot, docsDir, service } = cowriteServiceOn({ 'prd/a.md': DRAFT_PRD, 'idea/b.md': DRAFT_IDEA })
    const { before, plan } = admit(service, 'prd-00001-x', 'prd/a.md')
    const advance = claim({ kind: 'advance', sourceId: 'idea-00001-y', targetType: 'spec', idPrefix: 'spec-00002-' })
    write(docsDir, 'prd/a.md', `${DRAFT_PRD}written together\n`)
    write(docsDir, 'spec/spec-00002-new.md', doc({ id: 'spec-00002-new', type: 'spec', status: 'draft' }, '# New\n'))

    await service.commitCowriteChanges(plan, before, [advance])

    expect(existsSync(join(docsDir, 'spec/spec-00002-new.md'))).toBe(true)
    expect(lastCommitFiles(repoRoot)).toEqual(['docs/prd/a.md'])
  })

  // The other half of the same claim: every kind claims the file of the document
  // it is about, which is what a clarify and an audit write (design-00001 §11.3)
  it('leaves the document another running audit is writing where it is', async () => {
    const { docsDir, service } = cowriteServiceOn({ 'prd/a.md': DRAFT_PRD, 'idea/b.md': DRAFT_IDEA })
    const { before, plan } = admit(service, 'prd-00001-x', 'prd/a.md')
    const audit = claim({ kind: 'audit', sourceId: 'idea-00001-y' })
    write(docsDir, 'idea/b.md', `${DRAFT_IDEA}the audit session wrote this\n`)

    await service.commitCowriteChanges(plan, before, [audit])

    expect(onDisk(docsDir, 'idea/b.md')).toBe(`${DRAFT_IDEA}the audit session wrote this\n`)
  })

  /**
   * The reading the claim replaced: «everything that moved since the other
   * session's snapshot» is a reading of the disk, and the disk holds this
   * session's own strays too — so one concurrent session of any kind would exempt
   * them all and switch the whole filter off (design-00001 §11.3).
   */
  it('restores its own stray write while another session is running', async () => {
    const { docsDir, service } = cowriteServiceOn({ 'prd/a.md': DRAFT_PRD, 'idea/b.md': DRAFT_IDEA })
    const { before, plan } = admit(service, 'prd-00001-x', 'prd/a.md')
    const audit = claim({ kind: 'audit', sourceId: 'prd-00009-elsewhere' })
    write(docsDir, 'idea/b.md', `${DRAFT_IDEA}this session had no business here\n`)

    await service.commitCowriteChanges(plan, before, [audit])

    expect(onDisk(docsDir, 'idea/b.md')).toBe(DRAFT_IDEA)
  })

  /**
   * A concurrent cowrite's brand-new reference (design-00001 §11.3): it is under
   * `reference/`, it is not in HEAD and it is not in this session's dirt, so the
   * classification alone would take it for this session's own candidate — staged
   * into the wrong commit, or deleted from under a session that is still writing
   * it. The exemption is judged first, so it is neither.
   */
  it('neither stages nor deletes a reference another running cowrite created', async () => {
    const { repoRoot, docsDir, service } = cowriteServiceOn({ 'prd/a.md': DRAFT_PRD, 'idea/b.md': DRAFT_IDEA })
    const { before, plan } = admit(service, 'prd-00001-x', 'prd/a.md')
    const other = claim({ kind: 'cowrite', sourceId: 'idea-00001-y', targetPath: 'idea/b.md' })
    write(docsDir, 'prd/a.md', `${DRAFT_PRD}written together\n`)
    write(docsDir, 'reference/reference-00001-theirs.md', REFERENCE('reference-00001-theirs'))

    const outcome = await service.commitCowriteChanges(plan, before, [other])

    expect(existsSync(join(docsDir, 'reference/reference-00001-theirs.md'))).toBe(true)
    expect(lastCommitFiles(repoRoot)).toEqual(['docs/prd/a.md'])
    expect(outcome.problems).toEqual([])
  })

  /**
   * The hole the git reading alone leaves (spec-00006-FR-6): the owner had unsaved
   * edits to another document when the session started, and the agent wrote that
   * document back to exactly what HEAD holds. Git now calls the path clean, so
   * `changedSince` never names it — and the owner's edits exist nowhere but in the
   * content baseline. The filter walks that baseline too, and puts them back.
   */
  it('restores a path the session reverted to its committed content', async () => {
    const { docsDir, service } = cowriteServiceOn({ 'prd/a.md': DRAFT_PRD, 'idea/b.md': DRAFT_IDEA })
    const kept = `${DRAFT_IDEA}the owner had not saved this yet\n`
    write(docsDir, 'idea/b.md', kept)
    const { before, plan } = admit(service, 'prd-00001-x', 'prd/a.md')
    write(docsDir, 'prd/a.md', `${DRAFT_PRD}written together\n`)
    // Back to HEAD, byte for byte: no git reading of the tree can see this move.
    write(docsDir, 'idea/b.md', DRAFT_IDEA)

    await service.commitCowriteChanges(plan, before)

    expect(onDisk(docsDir, 'idea/b.md')).toBe(kept)
  })

  // The same walk must not restore a path that is genuinely back where it started:
  // a session that touched a file and undid its own change left nothing to put back
  it('leaves a path the session put back to the text it inherited', async () => {
    const { docsDir, service } = cowriteServiceOn({ 'prd/a.md': DRAFT_PRD, 'idea/b.md': DRAFT_IDEA })
    const inherited = `${DRAFT_IDEA}dirty before the session\n`
    write(docsDir, 'idea/b.md', inherited)
    const { before, plan } = admit(service, 'prd-00001-x', 'prd/a.md')

    const outcome = await service.commitCowriteChanges(plan, before)

    expect(onDisk(docsDir, 'idea/b.md')).toBe(inherited)
    expect(outcome.problems).toEqual([])
  })

  /**
   * A restore that cannot run is reported and nothing more (spec-00006-FR-8): the
   * file is left as the session wrote it, because a filter that cannot put a path
   * back must not go on to destroy it — and one path must not bring the whole
   * collapse down.
   */
  it('reports a restore that failed and leaves that file as the session wrote it', async () => {
    class NoRestore extends GitLayer {
      override restoreFromHead(): void {
        throw new Error('the index is locked')
      }
    }
    const { repoRoot, docsDir } = makeRepo({ 'prd/a.md': DRAFT_PRD, 'idea/b.md': DRAFT_IDEA })
    const service = new DocService(repoRoot, docsDir, cowriteConfig(), new NoRestore(repoRoot))
    const { before, plan } = admit(service, 'prd-00001-x', 'prd/a.md')
    write(docsDir, 'prd/a.md', `${DRAFT_PRD}written together\n`)
    write(docsDir, 'idea/b.md', `${DRAFT_IDEA}out of scope\n`)

    const outcome = await service.commitCowriteChanges(plan, before)

    expect(outcome.problems).toEqual(['docs/idea/b.md could not be put back: the index is locked'])
    expect(onDisk(docsDir, 'idea/b.md')).toBe(`${DRAFT_IDEA}out of scope\n`)
    expect(outcome.committed).toBe(true)
  })

  // The directory may have gone with the file (design-00001 §11.3): the restore
  // makes it again rather than falling over on an ENOENT
  it('restores an inherited path whose whole directory the session removed', async () => {
    const { docsDir, service } = cowriteServiceOn({ 'prd/a.md': DRAFT_PRD, 'idea/b.md': DRAFT_IDEA })
    const kept = `${DRAFT_IDEA}the owner had not saved this yet\n`
    write(docsDir, 'idea/b.md', kept)
    const { before, plan } = admit(service, 'prd-00001-x', 'prd/a.md')
    rmSync(join(docsDir, 'idea'), { recursive: true })

    const outcome = await service.commitCowriteChanges(plan, before)

    expect(onDisk(docsDir, 'idea/b.md')).toBe(kept)
    expect(outcome.problems).toEqual([])
  })

  // spec-00006-AC-6.6 — the file spec-00001-FR-20 keeps is not the filter's to destroy
  it('leaves a path whose own commit failed exactly where it is', async () => {
    class NoCommits extends GitLayer {
      override async commit(): Promise<CommitOutcome> {
        return { committed: false, error: 'the index is locked' }
      }
    }
    const { repoRoot, docsDir } = makeRepo({ 'prd/a.md': DRAFT_PRD, 'idea/b.md': DRAFT_IDEA })
    const service = new DocService(repoRoot, docsDir, cowriteConfig(), new NoCommits(repoRoot))
    const { before, plan } = admit(service, 'prd-00001-x', 'prd/a.md')
    const base = service.read('idea-00001-y')
    const kept = `${DRAFT_IDEA}the owner saved this and the commit failed\n`
    expect((await service.save('idea-00001-y', kept, base.hash)).committed).toBe(false)

    await service.commitCowriteChanges(plan, before)

    expect(onDisk(docsDir, 'idea/b.md')).toBe(kept)
    expect(git(repoRoot, 'status', '--porcelain', '--', 'docs/idea/b.md')).toMatch(/idea\/b\.md/)
  })

  /**
   * The retention is over the moment the path lands (design-00001 §11.3 (b)): the
   * write path kept a file its own commit could not take, a later commit staged it,
   * and from then on it is an ordinary committed document — so a cowrite that
   * writes it out of scope has it restored like any other. A retention that never
   * cleared would leave that stray write in the working tree for good.
   */
  it('stops exempting a retained path once a later commit has staged it', async () => {
    class FailsFirst extends GitLayer {
      private first = true
      override async commit(paths: string[], message: string): Promise<CommitOutcome> {
        if (!this.first) return super.commit(paths, message)
        this.first = false
        return { committed: false, error: 'the index is locked' }
      }
    }
    const { repoRoot, docsDir } = makeRepo({ 'prd/a.md': DRAFT_PRD, 'idea/b.md': DRAFT_IDEA })
    const service = new DocService(repoRoot, docsDir, cowriteConfig(), new FailsFirst(repoRoot))
    const owned = `${DRAFT_IDEA}the owner saved this and the commit failed\n`
    const base = service.read('idea-00001-y')
    const beforeRetry = service.snapshotDocs()
    expect((await service.save('idea-00001-y', owned, base.hash)).committed).toBe(false)

    // The path lands on the next commit that stages it — a session's, not a write
    // path's — and the retention has nothing left to protect.
    expect((await service.commitSessionChanges('idea-00001-y', beforeRetry, 'clarify')).committed).toBe(true)
    const { before, plan } = admit(service, 'prd-00001-x', 'prd/a.md')
    write(docsDir, 'idea/b.md', `${owned}and this session had no business here\n`)

    await service.commitCowriteChanges(plan, before)

    expect(onDisk(docsDir, 'idea/b.md')).toBe(owned)
  })

  // spec-00006-AC-6.7 — a deletion is no landed write BR-30 authorises
  it('stages no deletion when the target is gone, and lands the rest of the scope', async () => {
    const { repoRoot, docsDir, service } = cowriteServiceOn({ 'prd/a.md': DRAFT_PRD })
    const { before, plan } = admit(service, 'prd-00001-x', 'prd/a.md')
    rmSync(join(docsDir, 'prd/a.md'))
    write(docsDir, 'reference/reference-00001-notes.md', REFERENCE('reference-00001-notes'))

    const outcome = await service.commitCowriteChanges(plan, before)

    expect(outcome.problems.some((problem) => /no longer on disk/.test(problem))).toBe(true)
    expect(lastCommitFiles(repoRoot)).toEqual(['docs/reference/reference-00001-notes.md'])
    expect(existsSync(join(docsDir, 'prd/a.md'))).toBe(false)
  })

  // spec-00006-AC-8.1
  // rule-00001-AC-30.1
  it('commits the target and a well-formed new reference in one commit, the status kept', async () => {
    const { repoRoot, docsDir, service } = cowriteServiceOn({ 'prd/a.md': DRAFT_PRD })
    const { before, plan } = admit(service, 'prd-00001-x', 'prd/a.md')
    write(docsDir, 'prd/a.md', `${DRAFT_PRD}written together\n`)
    write(docsDir, 'reference/reference-00001-notes.md', REFERENCE('reference-00001-notes'))

    const outcome = await service.commitCowriteChanges(plan, before)

    expect(outcome).toMatchObject({ committed: true, problems: [] })
    expect(lastCommitMessage(repoRoot)).toBe('wb(cowrite): prd-00001-x')
    expect(lastCommitFiles(repoRoot).sort()).toEqual([
      'docs/prd/a.md',
      'docs/reference/reference-00001-notes.md',
    ])
    expect(onDisk(docsDir, 'prd/a.md')).toContain('status: draft')
  })

  // spec-00006-AC-8.4 — the numbering is judged over the set, so both land
  it('commits several well-formed references taking a contiguous run of numbers', async () => {
    const { repoRoot, docsDir, service } = cowriteServiceOn({
      'prd/a.md': DRAFT_PRD,
      'reference/old.md': REFERENCE('reference-00001-old'),
    })
    const { before, plan } = admit(service, 'prd-00001-x', 'prd/a.md')
    write(docsDir, 'reference/reference-00002-a.md', REFERENCE('reference-00002-a'))
    write(docsDir, 'reference/reference-00003-b.md', REFERENCE('reference-00003-b'))

    await service.commitCowriteChanges(plan, before)

    expect(lastCommitFiles(repoRoot).sort()).toEqual([
      'docs/reference/reference-00002-a.md',
      'docs/reference/reference-00003-b.md',
    ])
  })

  /**
   * spec-00006-AC-6.3 with AC-8.4, the two-session case whole: both cowrites were
   * admitted at the same moment and told to start at 00002, and this one collapses
   * second — the other's `reference-00002-theirs` has landed already. A taken
   * number is a per-file reading, so only the colliding candidate dies; the rest of
   * this session's run is judged against the maximum that now includes what landed,
   * and 00003 is exactly one above it.
   */
  // spec-00006-AC-6.3
  // spec-00006-AC-8.4
  it('drops only the reference whose number landed first, and lands the rest of the run', async () => {
    const { repoRoot, docsDir, service } = cowriteServiceOn({
      'prd/a.md': DRAFT_PRD,
      'reference/reference-00001-old.md': REFERENCE('reference-00001-old'),
    })
    const { before, plan } = admit(service, 'prd-00001-x', 'prd/a.md')
    write(docsDir, 'reference/reference-00002-mine.md', REFERENCE('reference-00002-mine'))
    write(docsDir, 'reference/reference-00003-mine.md', REFERENCE('reference-00003-mine'))
    // The other session's document, committed while this one was still writing.
    write(docsDir, 'reference/reference-00002-theirs.md', REFERENCE('reference-00002-theirs'))
    git(repoRoot, 'add', 'docs/reference/reference-00002-theirs.md')
    git(repoRoot, 'commit', '-q', '-m', 'wb(cowrite): idea-00001-y')

    const outcome = await service.commitCowriteChanges(plan, before)

    expect(existsSync(join(docsDir, 'reference/reference-00002-mine.md'))).toBe(false)
    expect(lastCommitFiles(repoRoot)).toEqual(['docs/reference/reference-00003-mine.md'])
    expect(outcome.problems).toEqual([
      'docs/reference/reference-00002-mine.md did not land: its number is already taken by another reference document',
    ])
    expect(existsSync(join(docsDir, 'reference/reference-00002-theirs.md'))).toBe(true)
  })

  // spec-00006-AC-8.2
  it('makes no commit when the session left nothing behind', async () => {
    const { repoRoot, service } = cowriteServiceOn({ 'prd/a.md': DRAFT_PRD })
    const commits = commitCount(repoRoot)
    const { before, plan } = admit(service, 'prd-00001-x', 'prd/a.md')

    expect(await service.commitCowriteChanges(plan, before)).toEqual({ committed: false, problems: [] })
    expect(commitCount(repoRoot)).toBe(commits)
  })

  // spec-00006-AC-6.3 — the parallel session that collapsed first has landed its
  // number, so the one that collapses second no longer sits in the run
  it('filters a reference whose number a document that landed first has taken', async () => {
    const { repoRoot, docsDir, service } = cowriteServiceOn({
      'prd/a.md': DRAFT_PRD,
      'reference/reference-00001-first.md': REFERENCE('reference-00001-first'),
    })
    const { before, plan } = admit(service, 'prd-00001-x', 'prd/a.md')
    write(docsDir, 'prd/a.md', `${DRAFT_PRD}written together\n`)
    write(docsDir, 'reference/reference-00001-second.md', REFERENCE('reference-00001-second'))

    const outcome = await service.commitCowriteChanges(plan, before)

    expect(existsSync(join(docsDir, 'reference/reference-00001-second.md'))).toBe(false)
    expect(outcome.problems.some((problem) => /did not land/.test(problem))).toBe(true)
    expect(lastCommitFiles(repoRoot)).toEqual(['docs/prd/a.md'])
  })

  // rule-00001-AC-30.4 — an id another document already declares does not land
  it('filters a reference whose id another document declares, and lands the rest', async () => {
    const { repoRoot, docsDir, service } = cowriteServiceOn({
      'prd/a.md': DRAFT_PRD,
      'reference/legacy.md': REFERENCE('reference-00001-notes'),
    })
    const { before, plan } = admit(service, 'prd-00001-x', 'prd/a.md')
    write(docsDir, 'prd/a.md', `${DRAFT_PRD}written together\n`)
    write(docsDir, 'reference/reference-00001-notes.md', REFERENCE('reference-00001-notes'))

    const outcome = await service.commitCowriteChanges(plan, before)

    expect(existsSync(join(docsDir, 'reference/reference-00001-notes.md'))).toBe(false)
    expect(outcome.problems.some((problem) => /already the id of another document/.test(problem))).toBe(true)
    expect(lastCommitFiles(repoRoot)).toEqual(['docs/prd/a.md'])
  })

  // The reserved numbers of the sessions still running count as existing
  // (spec-00003-FR-1's reading of rule-00001-BR-18)
  it('filters a reference that took a number a running session holds', async () => {
    const { docsDir, service } = cowriteServiceOn({ 'prd/a.md': DRAFT_PRD })
    const { before, plan } = admit(service, 'prd-00001-x', 'prd/a.md')
    write(docsDir, 'reference/reference-00001-notes.md', REFERENCE('reference-00001-notes'))

    await service.commitCowriteChanges(plan, before, [], [1])

    expect(existsSync(join(docsDir, 'reference/reference-00001-notes.md'))).toBe(false)
  })

  // The second birth path of rule-00001-BR-26 is a birth, not a licence over the
  // folder: an existing reference the session rewrote is out of scope
  it('restores an existing reference the session rewrote', async () => {
    const { docsDir, service } = cowriteServiceOn({
      'prd/a.md': DRAFT_PRD,
      'reference/reference-00001-old.md': REFERENCE('reference-00001-old'),
    })
    const { before, plan } = admit(service, 'prd-00001-x', 'prd/a.md')
    write(docsDir, 'reference/reference-00001-old.md', `${REFERENCE('reference-00001-old')}rewritten\n`)

    await service.commitCowriteChanges(plan, before)

    expect(onDisk(docsDir, 'reference/reference-00001-old.md')).toBe(REFERENCE('reference-00001-old'))
  })

  it('deletes a file under reference/ that is no document at all', async () => {
    const { docsDir, service } = cowriteServiceOn({ 'prd/a.md': DRAFT_PRD })
    const { before, plan } = admit(service, 'prd-00001-x', 'prd/a.md')
    write(docsDir, 'reference/notes.txt', 'loose notes\n')

    const outcome = await service.commitCowriteChanges(plan, before)

    expect(existsSync(join(docsDir, 'reference/notes.txt'))).toBe(false)
    expect(outcome.committed).toBe(false)
  })

  // spec-00006-AC-8.3 — the commit does not distinguish a stop from a natural end;
  // there is one wrap-up, and a half-written product is a finding, not a refusal
  it('commits the filtered changes the same way when the session was stopped mid-write', async () => {
    const { repoRoot, docsDir, service } = cowriteServiceOn({ 'prd/a.md': DRAFT_PRD })
    const { before, plan } = admit(service, 'prd-00001-x', 'prd/a.md')
    write(docsDir, 'prd/a.md', `${DRAFT_PRD}half a sen`)
    write(docsDir, 'reference/reference-00001-half.md', '---\nid: reference-00001-half\n')

    const outcome = await service.commitCowriteChanges(plan, before)

    expect(lastCommitMessage(repoRoot)).toBe('wb(cowrite): prd-00001-x')
    expect(lastCommitFiles(repoRoot)).toEqual(['docs/prd/a.md'])
    expect(existsSync(join(docsDir, 'reference/reference-00001-half.md'))).toBe(false)
    expect(outcome.problems).not.toEqual([])
  })
})

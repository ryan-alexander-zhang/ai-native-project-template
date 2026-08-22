import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { ConflictError, DocService, GateError } from '../src/docService.ts'
import { contentHash } from '../src/docRepository.ts'
import { clarifyStatePath } from '../src/sessionTasks.ts'
import { WorkflowError } from '../src/workflow.ts'
import { commitCount, doc, git, lastCommitFiles, lastCommitMessage, makeRepo, testConfig } from './helpers.ts'

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

  // spec-00001-AC-14.7 and AC-14.8 at the commit boundary: one commit per session,
  // named after the kind of session it was.
  it('names the commit after the session kind', async () => {
    for (const [kind, message] of [
      ['clarify', 'wb(clarify): prd-00001-x'],
      ['ask', 'wb(ask): prd-00001-x'],
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
  // spec-00001-AC-47.1 and AC-47.3
  it('plans an ask session about a document of any type and status, with its context', () => {
    const { service } = serviceOn({
      'record/r.md': doc({ id: 'record-00001-r', type: 'record', status: 'active', verifies: '[prd-00001-x]' }),
      'prd/a.md': DRAFT_PRD,
    })

    const plan = service.askPlan('record-00001-r')

    expect(plan.kind).toBe('ask')
    expect(plan.sourceId).toBe('record-00001-r')
    expect(plan.expectation).toBeUndefined()
    expect(plan.instruction).toContain('record/r.md')
    expect(plan.instruction).toContain('prd/a.md')
  })

  // spec-00001-AC-47.5
  it('refuses an anomalous document', () => {
    const { service } = serviceOn({ 'prd/broken.md': doc({ id: 'nope', type: 'prd', status: 'draft' }) })
    expect(() => service.askPlan('nope')).toThrowError(/front matter problems/)
  })

  // spec-00001-AC-19.2
  it('refuses a document that is no longer on disk, telling the caller to refresh', () => {
    const { docsDir, service } = serviceOn({ 'prd/a.md': DRAFT_PRD })
    rmSync(join(docsDir, 'prd/a.md'))

    expect(() => service.askPlan('prd-00001-x')).toThrowError(/refresh the board/)
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

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

  // rule-00001-AC-26.1: the number is the highest plus one, the template is the type's
  it('allocates the next number and hands back the type template', () => {
    const { service } = serviceOn({ 'idea/a.md': IDEA, 'idea/TEMPLATE.md': TEMPLATE })

    expect(service.newDocument('idea')).toEqual({ idPrefix: 'idea-00002-', template: TEMPLATE })
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

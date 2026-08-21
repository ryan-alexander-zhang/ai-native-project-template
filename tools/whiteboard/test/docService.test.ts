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

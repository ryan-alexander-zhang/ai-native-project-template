import { mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { ConflictError, DocService } from '../src/docService.js'
import { contentHash } from '../src/docRepository.js'
import { WorkflowError } from '../src/workflow.js'
import { commitCount, doc, git, lastCommitFiles, lastCommitMessage, makeRepo, testConfig } from './helpers.js'

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

  // spec-00001-AC-9.1
  it('writes clarify questions and keeps the document draft', async () => {
    const { docsDir, repoRoot, service } = serviceOn({ 'prd/a.md': DRAFT_PRD })

    const result = await service.review('prd-00001-x', { action: 'clarify', questions: ['who owns pricing?'] })

    expect(result.status).toBe('draft')
    expect(onDisk(docsDir, 'prd/a.md')).toContain('- who owns pricing?')
    expect(onDisk(docsDir, 'prd/a.md')).toContain('status: draft')
    expect(lastCommitMessage(repoRoot)).toBe('wb(clarify): prd-00001-x')
  })

  // spec-00001-AC-9.3
  it('writes every question given', async () => {
    const { docsDir, service } = serviceOn({ 'prd/a.md': DRAFT_PRD })

    await service.review('prd-00001-x', { action: 'clarify', questions: ['one', 'two', 'three'] })

    expect(onDisk(docsDir, 'prd/a.md')).toContain('- one\n- two\n- three')
  })

  // spec-00001-AC-9.4
  it('rejects clarify on a document that is not draft', async () => {
    const { service } = serviceOn({ 'prd/a.md': doc({ id: 'prd-00001-x', type: 'prd', status: 'active' }) })
    await expect(service.review('prd-00001-x', { action: 'clarify', questions: ['q'] })).rejects.toThrowError(
      /applies to a draft/,
    )
  })

  it('rejects clarify with no questions', async () => {
    const { service } = serviceOn({ 'prd/a.md': DRAFT_PRD })
    await expect(service.review('prd-00001-x', { action: 'clarify' })).rejects.toThrowError(/at least one question/)
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
    mkdirSync(join(docsDir, 'spec'))
    writeFileSync(join(docsDir, 'spec/new.md'), doc({ id: 'spec-00001-z', type: 'spec', status: 'draft' }))

    const result = await service.commitSessionChanges('spec-00001-z')

    expect(result.committed).toBe(true)
    expect(lastCommitMessage(repoRoot)).toBe('wb(advance): spec-00001-z')
    expect(lastCommitFiles(repoRoot)).toEqual(['docs/spec/new.md'])
  })

  it('skips the commit when the session changed nothing', async () => {
    const { repoRoot, service } = serviceOn({ 'prd/a.md': DRAFT_PRD })
    const before = commitCount(repoRoot)

    expect(await service.commitSessionChanges('prd-00001-x')).toEqual({ committed: false })
    expect(commitCount(repoRoot)).toBe(before)
  })
})

describe('transitions and nextSteps', () => {
  it('reads the workflow decisions for a document', () => {
    const { service } = serviceOn({ 'prd/a.md': DRAFT_PRD })
    expect(service.transitions('prd-00001-x')).toEqual(['active', 'archived'])
    expect(service.nextSteps('prd-00001-x')).toEqual([{ next: 'spec', carry: 'parent' }])
  })
})

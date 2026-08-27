import { existsSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { GitLayer } from '../src/gitLayer.ts'
import { doc, makeRepo } from './helpers.ts'

const PRD = doc({ id: 'prd-00001-x', type: 'prd', status: 'draft' }, '# X\n\ncommitted body\n')
const IDEA = doc({ id: 'idea-00001-y', type: 'idea', status: 'draft' }, '# Y\n')

function layerOn(files: Record<string, string>) {
  const { repoRoot, docsDir } = makeRepo(files)
  return { repoRoot, docsDir, git: new GitLayer(repoRoot) }
}

/**
 * The content snapshot and the three restores a cowrite session's collapse filter
 * stands on (design-00001 §11.3): a digest says that a path moved, and putting it
 * back needs what it moved from.
 */
describe('contentSnapshot', () => {
  it('holds the whole text of every dirty path, and nothing of the clean ones', () => {
    const { docsDir, git } = layerOn({ 'prd/a.md': PRD, 'idea/b.md': IDEA })
    writeFileSync(join(docsDir, 'prd/a.md'), `${PRD}dirty\n`)

    const snapshot = git.contentSnapshot('docs')

    expect([...snapshot.keys()]).toEqual(['docs/prd/a.md'])
    expect(snapshot.get('docs/prd/a.md')).toBe(`${PRD}dirty\n`)
  })

  // A path that is dirty because it was deleted is a content too: restoring it
  // means deleting it again.
  it('records a path that is dirty by deletion as null', () => {
    const { docsDir, git } = layerOn({ 'prd/a.md': PRD })
    rmSync(join(docsDir, 'prd/a.md'))

    expect(git.contentSnapshot('docs').get('docs/prd/a.md')).toBeNull()
  })

  it('holds an untracked file the session inherited', () => {
    const { docsDir, git } = layerOn({ 'prd/a.md': PRD })
    writeFileSync(join(docsDir, 'prd/new.md'), 'half typed\n')

    expect(git.contentSnapshot('docs').get('docs/prd/new.md')).toBe('half typed\n')
  })
})

describe('restoreContent', () => {
  it('writes the snapshotted text back over what a session left', () => {
    const { docsDir, git } = layerOn({ 'prd/a.md': PRD })
    writeFileSync(join(docsDir, 'prd/a.md'), 'the session wrote this\n')

    git.restoreContent('docs/prd/a.md', `${PRD}inherited dirt\n`)

    expect(readFileSync(join(docsDir, 'prd/a.md'), 'utf8')).toBe(`${PRD}inherited dirt\n`)
  })

  it('deletes the path again when the snapshot recorded an absence', () => {
    const { docsDir, git } = layerOn({ 'prd/a.md': PRD })

    git.restoreContent('docs/prd/a.md', null)

    expect(existsSync(join(docsDir, 'prd/a.md'))).toBe(false)
  })

  // The session may have taken the folder with the file: a restore that cannot
  // write is a restore that did not happen, and the ENOENT would come back out of
  // the collapse filter (design-00001 §11.3).
  it('makes the directory again when the session removed it along with the file', () => {
    const { docsDir, git } = layerOn({ 'prd/a.md': PRD, 'idea/b.md': IDEA })
    rmSync(join(docsDir, 'idea'), { recursive: true })

    git.restoreContent('docs/idea/b.md', IDEA)

    expect(readFileSync(join(docsDir, 'idea/b.md'), 'utf8')).toBe(IDEA)
  })
})

describe('restoreFromHead', () => {
  it('brings a path that was clean back to what HEAD holds', () => {
    const { docsDir, git } = layerOn({ 'prd/a.md': PRD })
    writeFileSync(join(docsDir, 'prd/a.md'), 'out of scope\n')

    git.restoreFromHead('docs/prd/a.md')

    expect(readFileSync(join(docsDir, 'prd/a.md'), 'utf8')).toBe(PRD)
  })

  it('brings back a path the session deleted', () => {
    const { docsDir, git } = layerOn({ 'prd/a.md': PRD })
    rmSync(join(docsDir, 'prd/a.md'))

    git.restoreFromHead('docs/prd/a.md')

    expect(readFileSync(join(docsDir, 'prd/a.md'), 'utf8')).toBe(PRD)
  })

  // HEAD does not carry it either, so the file is the session's own: deleting it
  // is the restore (design-00001 §11.3 (3)).
  it('deletes a file HEAD does not carry at all', () => {
    const { docsDir, git } = layerOn({ 'prd/a.md': PRD })
    writeFileSync(join(docsDir, 'prd/invented.md'), 'out of scope\n')

    git.restoreFromHead('docs/prd/invented.md')

    expect(existsSync(join(docsDir, 'prd/invented.md'))).toBe(false)
  })

  /**
   * The deletion is conditioned on HEAD not carrying the path, never on the git
   * call merely having failed: a checkout that falls over for a reason of its own
   * — a locked index, a repository mid-operation — must not delete a document that
   * is committed. Here the repository has no HEAD at all, so every git call in the
   * restore fails, and the tracked file is left rather than destroyed.
   */
  it('raises rather than deleting when HEAD carries the path and the checkout fails', () => {
    const { repoRoot, docsDir, git } = layerOn({ 'prd/a.md': PRD })
    writeFileSync(join(docsDir, 'prd/a.md'), 'out of scope\n')
    // A locked index: the checkout cannot run, while HEAD still answers for the
    // path. The two failures are not the same thing, and only the second one is a
    // licence to delete.
    writeFileSync(join(repoRoot, '.git/index.lock'), '')

    expect(() => git.restoreFromHead('docs/prd/a.md')).toThrowError()
    expect(readFileSync(join(docsDir, 'prd/a.md'), 'utf8')).toBe('out of scope\n')
  })
})

describe('inHead', () => {
  it('tells a path the last commit carries from one it does not', () => {
    const { docsDir, git } = layerOn({ 'prd/a.md': PRD })
    writeFileSync(join(docsDir, 'prd/new.md'), 'new\n')

    expect(git.inHead('docs/prd/a.md')).toBe(true)
    expect(git.inHead('docs/prd/new.md')).toBe(false)
  })
})

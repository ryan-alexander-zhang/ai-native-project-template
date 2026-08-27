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
})

describe('inHead', () => {
  it('tells a path the last commit carries from one it does not', () => {
    const { docsDir, git } = layerOn({ 'prd/a.md': PRD })
    writeFileSync(join(docsDir, 'prd/new.md'), 'new\n')

    expect(git.inHead('docs/prd/a.md')).toBe(true)
    expect(git.inHead('docs/prd/new.md')).toBe(false)
  })
})

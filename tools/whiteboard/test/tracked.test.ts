import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { findRepoRoot } from '../src/config.ts'

/**
 * What a fresh clone gets (issue-00017). Every other test in this suite reads
 * the working copy, where an ignored-but-present file is indistinguishable from
 * a tracked one — so a module git never took in passes every one of them here
 * and fails to resolve for anybody who clones. The only honest question is what
 * `git ls-files` answers, and it is asked of the paths the build cannot start
 * without.
 */
describe('the files a fresh clone has to get', () => {
  const repoRoot = findRepoRoot(process.cwd())
  const tracked = new Set(
    execFileSync('git', ['ls-files'], { cwd: repoRoot, encoding: 'utf8' }).split('\n').filter(Boolean),
  )

  // `@/lib/utils` is imported by every vendored shadcn component, so a clone
  // without it fails to import all of the web test files, not one of them.
  it('tracks the module every vendored ui component imports', () => {
    expect([...tracked].filter((path) => path === 'tools/whiteboard/web/src/lib/utils.ts')).toEqual([
      'tools/whiteboard/web/src/lib/utils.ts',
    ])
  })

  // `ainpt update` folds the template into every downstream project with
  // `git merge-file`, which refuses any file git calls binary — a NUL byte in
  // the first 8000 bytes. One raw control byte in a tracked source is an
  // unresolvable conflict in every project that updates (issue-00021).
  it('tracks only files git can merge as text', () => {
    const binary = [...tracked].filter((path) =>
      readFileSync(join(repoRoot, path)).subarray(0, 8000).includes(0),
    )
    expect(binary).toEqual([])
  })
})

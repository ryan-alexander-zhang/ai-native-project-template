import { execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { type SimpleGit, simpleGit } from 'simple-git'

export type ActionKind = 'edit' | 'status' | 'accept' | 'clarify' | 'advance'

/**
 * What the dirty files under a directory held at one moment: repo-relative path
 * to a digest of its content. An advance session's commit is scoped against the
 * one taken before it started (design-00001 §4).
 */
export type DirtySnapshot = ReadonlyMap<string, string>

/** The digest of a path that is not there — a deletion is a content too. */
const ABSENT = 'absent'

export interface CommitOutcome {
  committed: boolean
  error?: string
}

export function commitMessage(action: ActionKind, docId: string): string {
  return `wb(${action}): ${docId}`
}

/**
 * Commits whiteboard actions. Only the paths an action touched are ever staged —
 * an unrelated dirty file stays out of the commit (spec-00001-AC-14.2).
 */
export class GitLayer {
  private readonly git: SimpleGit
  private readonly repoRoot: string

  constructor(repoRoot: string) {
    this.git = simpleGit(repoRoot)
    this.repoRoot = repoRoot
  }

  /** Stage and commit exactly `paths`. A git failure leaves the files on disk (spec-00001-FR-20). */
  async commit(paths: string[], message: string): Promise<CommitOutcome> {
    if (paths.length === 0) return { committed: false }
    try {
      await this.git.add(paths)
      await this.git.commit(message, paths)
      return { committed: true }
    } catch (cause) {
      return { committed: false, error: (cause as Error).message }
    }
  }

  /** Repo-relative paths under `dir` that differ from the last commit. */
  async changedPaths(dir: string): Promise<string[]> {
    const status = await this.git.status()
    return status.files.map((file) => file.path).filter((path) => path.startsWith(`${dir}/`))
  }

  /**
   * The dirty files under `dir` right now, with a digest of each. Read
   * synchronously on purpose: the caller takes it to fence off an agent session,
   * and anything the session writes while an async read is in flight would land
   * in the snapshot as «already dirty» and be excluded from its own commit.
   */
  snapshot(dir: string): DirtySnapshot {
    const snapshot = new Map<string, string>()
    for (const path of this.dirtyPaths(dir)) snapshot.set(path, this.digest(path))
    return snapshot
  }

  /**
   * Paths under `dir` whose content differs from what `before` recorded — the
   * three dispositions of design-00001 §4 in one comparison: a path the snapshot
   * never had is staged, one whose digest still matches is another writer's dirt
   * and is excluded, and one whose digest moved was written into during the
   * window and is staged.
   */
  async changedSince(dir: string, before: DirtySnapshot): Promise<string[]> {
    const paths = await this.changedPaths(dir)
    return paths.filter((path) => before.get(path) !== this.digest(path))
  }

  private dirtyPaths(dir: string): string[] {
    // NUL-separated so no path is ever quoted or escaped.
    const status = execFileSync('git', ['status', '--porcelain=v1', '-z', '--untracked-files=all', '--', dir], {
      cwd: this.repoRoot,
      encoding: 'utf8',
    })
    const tokens = status.split('\0').filter((token) => token.length > 0)
    const paths: string[] = []
    while (tokens.length > 0) {
      const entry = tokens.shift()!
      paths.push(entry.slice(3))
      // A rename or copy carries the original path in the next token, and both
      // ends of it are dirty.
      if (/^[RC]/.test(entry)) paths.push(...tokens.splice(0, 1))
    }
    return paths
  }

  private digest(path: string): string {
    try {
      return createHash('sha256').update(readFileSync(join(this.repoRoot, path))).digest('hex')
    } catch {
      return ABSENT
    }
  }
}

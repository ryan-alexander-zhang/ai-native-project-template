import { execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { type SimpleGit, simpleGit } from 'simple-git'

/**
 * The action a commit names (spec-00001-FR-14, format per design-00001 §7). The
 * last five are the session kinds: one commit per session, named by the kind it
 * was (spec-00001-AC-14.4, AC-14.7, AC-14.8, AC-50.3; cowrite is the
 * twenty-second round's, spec-00006-FR-8).
 */
export type ActionKind = 'edit' | 'status' | 'accept' | 'create' | 'advance' | 'clarify' | 'ask' | 'audit' | 'cowrite'

/**
 * What the dirty files under a directory held at one moment: repo-relative path
 * to a digest of its content. An advance session's commit is scoped against the
 * one taken before it started (design-00001 §4).
 */
export type DirtySnapshot = ReadonlyMap<string, string>

/**
 * The full text of every dirty file under a directory at one moment — a cowrite
 * session's snapshot stores content, not digests, because its collapse filter
 * restores what it filters (design-00001 §11.3). `null` records a path that was
 * dirty by deletion: restoring it means deleting it again.
 */
export type ContentSnapshot = ReadonlyMap<string, string | null>

/** The digest of a path that is not there — a deletion is a content too. */
const ABSENT = 'absent'

/**
 * Every board commit skips the repo's commit hooks (decision-00008 §2 第 6 条,
 * design-00001 §7). The hook's audience is a hand-made commit, while the board
 * commits `draft` products by spec (spec-00001-FR-17, FR-53) and carries its own
 * review gates — one policy needs one enforcer, not two that stop each other.
 */
const NO_VERIFY = { '--no-verify': null }

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
      await this.git.commit(message, paths, NO_VERIFY)
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

  /**
   * The dirty files under `dir` right now, with the **whole text** of each — the
   * baseline a cowrite session's collapse filter restores from
   * (design-00001 §11.3): a digest says a path moved, and restoring needs what
   * it moved from. Synchronous for the same reason {@link snapshot} is, and
   * bounded the same way: the dirt as it stands when the session starts.
   *
   * A path that is dirty by deletion is recorded as `null`, which restores by
   * deleting it again; a clean path is not here at all, because HEAD is its
   * restore basis.
   */
  contentSnapshot(dir: string): ContentSnapshot {
    const snapshot = new Map<string, string | null>()
    for (const path of this.dirtyPaths(dir)) snapshot.set(path, this.readText(path))
    return snapshot
  }

  /**
   * The text a path holds right now, or `null` when it holds none — what tells a
   * snapshotted path that is back to its snapshot content from one that is not.
   * A path git no longer reports as dirty may still have moved: an agent that
   * reverted a pre-session edit to HEAD leaves nothing for `changedSince` to see
   * (design-00001 §11.3).
   */
  currentText(path: string): string | null {
    return this.readText(path)
  }

  /**
   * Put a snapshotted path back as it was: its text, or its absence
   * (design-00001 §11.3). The directory is made first — the session may have
   * removed the whole folder along with the file, and a restore that cannot
   * write is a restore that did not happen.
   */
  restoreContent(path: string, text: string | null): void {
    const absolute = join(this.repoRoot, path)
    if (text === null) {
      rmSync(absolute, { force: true })
      return
    }
    mkdirSync(dirname(absolute), { recursive: true })
    writeFileSync(absolute, text)
  }

  /**
   * Restore a path the snapshot never held — it was clean when the session
   * started, so HEAD is what it held (design-00001 §11.3). A path HEAD does not
   * carry either is one the session created outside its write scope: deleting it
   * is the restore.
   *
   * The deletion is conditioned on HEAD **not** carrying the path, never on the
   * checkout merely having failed: a git call that fell over for a reason of its
   * own — a locked index, a transient error — would otherwise delete a document
   * that is committed. A checkout that fails on a path HEAD does carry is raised,
   * so the caller reports it and leaves the file where it is.
   */
  restoreFromHead(path: string): void {
    if (!this.inHead(path)) {
      rmSync(join(this.repoRoot, path), { force: true })
      return
    }
    this.run(['checkout', 'HEAD', '--', path])
  }

  /** Whether HEAD carries that path — what tells a file the session created from one it rewrote. */
  inHead(path: string): boolean {
    try {
      this.run(['cat-file', '-e', `HEAD:${path}`])
      return true
    } catch {
      return false
    }
  }

  private run(args: string[]): string {
    return execFileSync('git', args, { cwd: this.repoRoot, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })
  }

  private readText(path: string): string | null {
    try {
      return readFileSync(join(this.repoRoot, path), 'utf8')
    } catch {
      return null
    }
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

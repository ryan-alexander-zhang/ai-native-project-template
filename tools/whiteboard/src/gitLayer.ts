import { type SimpleGit, simpleGit } from 'simple-git'

export type ActionKind = 'edit' | 'status' | 'accept' | 'clarify' | 'advance'

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

  constructor(repoRoot: string) {
    this.git = simpleGit(repoRoot)
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
}

import { writeFileSync } from 'node:fs'
import { join, relative } from 'node:path'
import type { FlowConfig, FlowStep } from './config.ts'
import {
  type DocContent,
  type DocGraph,
  type DocNode,
  contentHash,
  findNode,
  readDocContent,
  readGraph,
} from './docRepository.ts'
import { type ActionKind, type CommitOutcome, GitLayer, commitMessage } from './gitLayer.ts'
import { applyAccept, applyClarify, applyStatusChange, nextStepsFor, transitionsFor } from './workflow.ts'

/** The document changed under the action, or is gone; the caller must refresh (spec-00001-FR-5, FR-19). */
export class ConflictError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'ConflictError'
  }
}

export interface ActionResult extends CommitOutcome {
  status?: string
}

export interface ReviewInput {
  action: 'accept' | 'clarify'
  questions?: string[]
}

/**
 * The single write path: re-read from disk, let the workflow decide, write, commit.
 * Re-reading before every write is what makes a stale action fail instead of clobber.
 */
export class DocService {
  private readonly repoRoot: string
  private readonly docsDir: string
  private readonly config: FlowConfig
  private readonly git: GitLayer

  constructor(repoRoot: string, docsDir: string, config: FlowConfig, git: GitLayer = new GitLayer(repoRoot)) {
    this.repoRoot = repoRoot
    this.docsDir = docsDir
    this.config = config
    this.git = git
  }

  graph(): DocGraph {
    return readGraph(this.docsDir, this.config)
  }

  read(id: string): DocContent {
    return readDocContent(this.docsDir, this.require(id))
  }

  transitions(id: string): string[] {
    return transitionsFor(this.require(id), this.config)
  }

  nextSteps(id: string): FlowStep[] {
    return nextStepsFor(this.require(id), this.config)
  }

  /** spec-00001-FR-4 and FR-5: the edit lands only if the file still matches what was opened. */
  async save(id: string, content: string, baseHash: string): Promise<ActionResult> {
    const node = this.require(id)
    const current = this.readOrConflict(node)
    if (current.hash !== baseHash) {
      throw new ConflictError(`${id} changed on disk since it was opened`)
    }
    return this.write(node, content, 'edit')
  }

  /** spec-00001-FR-6 and FR-7. */
  async changeStatus(id: string, to: string): Promise<ActionResult> {
    const node = this.require(id)
    const current = this.readOrConflict(node)
    const updated = applyStatusChange(current.content, node, this.config, to)
    return { ...(await this.write(node, updated, 'status')), status: to }
  }

  /** spec-00001-FR-8 and FR-9. */
  async review(id: string, input: ReviewInput): Promise<ActionResult> {
    const node = this.require(id)
    const current = this.readOrConflict(node)
    if (input.action === 'accept') {
      const accepted = applyAccept(current.content, node, this.config)
      return { ...(await this.write(node, accepted.content, 'accept')), status: accepted.to }
    }
    const clarified = applyClarify(current.content, node, this.config, input.questions ?? [])
    return { ...(await this.write(node, clarified, 'clarify')), status: node.status }
  }

  /** Commit whatever an agent session left under docs/ (spec-00001-FR-14, advance). */
  async commitSessionChanges(docId: string): Promise<ActionResult> {
    const dir = relative(this.repoRoot, this.docsDir).split(/[\\/]/).join('/')
    const paths = await this.git.changedPaths(dir)
    return this.git.commit(paths, commitMessage('advance', docId))
  }

  private require(id: string): DocNode {
    const node = findNode(this.graph(), id)
    if (!node) throw new ConflictError(`${id} is not a document in this repo; refresh the board`)
    return node
  }

  private readOrConflict(node: DocNode): DocContent {
    try {
      return readDocContent(this.docsDir, node)
    } catch {
      throw new ConflictError(`${node.id} is no longer on disk; refresh the board`)
    }
  }

  private async write(node: DocNode, content: string, action: ActionKind): Promise<ActionResult> {
    const absolute = join(this.docsDir, node.path)
    writeFileSync(absolute, content)
    const repoPath = relative(this.repoRoot, absolute).split(/[\\/]/).join('/')
    return this.git.commit([repoPath], commitMessage(action, node.id))
  }
}

export { contentHash }

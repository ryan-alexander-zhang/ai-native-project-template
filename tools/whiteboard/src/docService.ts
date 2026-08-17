import { writeFileSync } from 'node:fs'
import { join, relative } from 'node:path'
import type { FlowConfig, FlowStep } from './config.ts'
import {
  type DocContent,
  type DocGraph,
  type DocNode,
  contentHash,
  findNode,
  readDocBody,
  readDocContent,
  readGraph,
} from './docRepository.ts'
import { type ActionKind, type CommitOutcome, type DirtySnapshot, GitLayer, commitMessage } from './gitLayer.ts'
import { type ItemsView, declaresItems, requirementView } from './requirements.ts'
import type { SessionPlan } from './sessionManager.ts'
import {
  askInstruction,
  clarifyInstruction,
  clarifyStatePath,
  readClarifyState,
  relatedDocPaths,
  removeClarifyState,
} from './sessionTasks.ts'
import {
  WorkflowError,
  applyAccept,
  applyStatusChange,
  assertAskable,
  assertClarifiable,
  nextStepsFor,
  transitionsFor,
} from './workflow.ts'

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

/**
 * Accept is the only review action that writes: clarify is an agent session now,
 * and it writes from inside the session (spec-00001-FR-9, decision-00006).
 */
export interface ReviewInput {
  action: 'accept'
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

  /**
   * The document's requirement items with their coverage (spec-00001-FR-31 …
   * FR-33). Every record in the repo is evidence, whatever its own status
   * (decision-00004 §5 裁定二); a type that declares no items yields none.
   */
  items(id: string): ItemsView {
    const graph = this.graph()
    const node = this.require(id, graph)
    if (!declaresItems(node.type)) return { items: [], diagnostics: [] }
    const records = graph.nodes
      .filter((candidate) => candidate.type === 'record')
      .map((record) => ({ id: record.id, body: readDocBody(this.docsDir, record) }))
    return requirementView({ id: node.id, body: readDocBody(this.docsDir, node) }, records)
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

  /** spec-00001-FR-8: accept promotes, and closes out that round of clarify. */
  async review(id: string, input: ReviewInput): Promise<ActionResult> {
    if (input.action !== 'accept') {
      throw new WorkflowError(`${JSON.stringify(input.action)} is not a review action`)
    }
    const node = this.require(id)
    const current = this.readOrConflict(node)
    const accepted = applyAccept(current.content, node, this.config)
    const result = await this.write(node, accepted.content, 'accept')
    // The promotion is the end of that round of clarify, so its progress has
    // nothing left to recover from (spec-00001-AC-46.6).
    removeClarifyState(this.repoRoot, node.id)
    return { ...result, status: accepted.to }
  }

  /**
   * What a clarify session for `id` is started with (spec-00001-FR-9 and FR-45):
   * refused unless the document is a draft of a clarifiable type and still on
   * disk (FR-19). The focus line comes from the flow config, which the startup
   * check guarantees carries one for every clarifiable type it declares (FR-48).
   */
  clarifyPlan(id: string): SessionPlan {
    const graph = this.graph()
    const node = this.require(id, graph)
    this.readOrConflict(node)
    assertClarifiable(node, this.config)
    return {
      kind: 'clarify',
      sourceId: node.id,
      instruction: clarifyInstruction({
        docPath: node.path,
        relatedPaths: relatedDocPaths(graph, node.id),
        focus: this.config.focus[node.type!]!,
        statePath: clarifyStatePath(node.id),
        state: readClarifyState(this.repoRoot, node.id),
      }),
    }
  }

  /** What an ask session for `id` is started with (spec-00001-FR-47): any status, any sound type. */
  askPlan(id: string): SessionPlan {
    const graph = this.graph()
    const node = this.require(id, graph)
    this.readOrConflict(node)
    assertAskable(node)
    return {
      kind: 'ask',
      sourceId: node.id,
      instruction: askInstruction({ docPath: node.path, relatedPaths: relatedDocPaths(graph, node.id) }),
    }
  }

  /**
   * What docs/ already held in dirt, to be taken before an agent session starts:
   * the baseline its commit is scoped against (spec-00001-AC-14.5, issue-00008).
   */
  snapshotDocs(): DirtySnapshot {
    return this.git.snapshot(this.docsPath())
  }

  /**
   * Commit what an agent session left under docs/ (spec-00001-FR-14): the content
   * that moved since `before`, never the dirt the session inherited. Nothing
   * moved, nothing committed (spec-00001-AC-14.6). `action` is the session's kind
   * — the commit says which of the three it was (spec-00001-AC-14.7, AC-14.8) —
   * and defaults to the advance every existing caller means.
   */
  async commitSessionChanges(
    docId: string,
    before: DirtySnapshot,
    action: ActionKind = 'advance',
  ): Promise<ActionResult> {
    const paths = await this.git.changedSince(this.docsPath(), before)
    return this.git.commit(paths, commitMessage(action, docId))
  }

  private docsPath(): string {
    return relative(this.repoRoot, this.docsDir).split(/[\\/]/).join('/')
  }

  private require(id: string, graph: DocGraph = this.graph()): DocNode {
    const node = findNode(graph, id)
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

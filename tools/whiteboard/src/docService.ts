import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join, relative } from 'node:path'
import type { FlowConfig, FlowStep } from './config.ts'
import {
  type DocContent,
  type DocGraph,
  type DocNode,
  collidingPaths,
  contentHash,
  declaredId,
  findNode,
  frontMatterId,
  parseDocId,
  readDocBody,
  readDocContent,
  readGraph,
} from './docRepository.ts'
import { type ActionKind, type CommitOutcome, type DirtySnapshot, GitLayer, commitMessage } from './gitLayer.ts'
import {
  type Coverage,
  type DocBody,
  type ItemsView,
  type RecordScan,
  declaresItems,
  requirementViewFrom,
  scanRecords,
} from './requirements.ts'
import { itemCoverage, resolvedGaps } from './resolvedGate.ts'
import type { SessionPlan } from './sessionManager.ts'
import { promotedStatus } from './statusRules.ts'
import {
  askInstruction,
  auditInstruction,
  clarifyInstruction,
  clarifyStatePath,
  readClarifyState,
  relatedDocPaths,
  removeClarifyState,
  typeReadmePath,
} from './sessionTasks.ts'
import {
  WorkflowError,
  allocateNumber,
  applyAccept,
  applyStatusChange,
  assertAskable,
  assertAuditable,
  assertClarifiable,
  assertEntryType,
  hasOpenQuestions,
  idPrefix,
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

/**
 * The resolved gate refused the transition (spec-00001-FR-52). A refused action
 * all the same, so it answers 422 like any other; the gaps ride along in the
 * body, which is what tells this refusal from a merely illegal transition
 * (design-00001 §7).
 */
export class GateError extends WorkflowError {
  readonly gaps: string[]

  constructor(message: string, gaps: string[]) {
    super(message)
    this.name = 'GateError'
    this.gaps = gaps
  }
}

export interface ActionResult extends CommitOutcome {
  status?: string
}

/**
 * One row of the global coverage view (spec-00002-FR-10 and FR-11,
 * design-00001 §7): a spec or a rule, its three counts, and every item with the
 * state those counts were taken from. The per-item states ride along rather
 * than waiting for a second call — the counts are derived from them, so they
 * are already in hand, and a row and its expansion then come from one snapshot.
 */
export interface CoverageRow {
  docId: string
  title: string
  verified: number
  failing: number
  uncovered: number
  items: Array<{ id: string; coverage: Coverage }>
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
  /** The parsed tree, held until something invalidates it (spec-00001 §7 非功能项). */
  private cached?: DocGraph
  /**
   * The body-parse cache of spec-00002 §7 (design-00001 §2): the graph cache
   * held the front matter alone, so `/items`, the resolved gate and the global
   * coverage view each re-read the whole tree. Two things are kept — a file's
   * body, by path, and the derivation over **every** record, by document — and
   * both die on the one `invalidate()` the graph dies on, so the two can never
   * stand on different states of the disk.
   */
  private readonly bodies = new Map<string, string>()
  private readonly views = new Map<string, ItemsView>()
  private scanned?: RecordScan

  constructor(repoRoot: string, docsDir: string, config: FlowConfig, git: GitLayer = new GitLayer(repoRoot)) {
    this.repoRoot = repoRoot
    this.docsDir = docsDir
    this.config = config
    this.git = git
  }

  graph(): DocGraph {
    this.cached ??= readGraph(this.docsDir, this.config)
    return this.cached
  }

  /**
   * Drop the parsed tree, so the next read parses again (spec-00001 §7 非功能项,
   * decision-00008 §2 第 8 条). The invalidation signals already exist: every
   * board write path invalidates on its way out, and the watcher of FR-42
   * invalidates for everything written from outside.
   */
  invalidate(): void {
    this.cached = undefined
    this.bodies.clear()
    this.views.clear()
    this.scanned = undefined
  }

  /** A document's body, read off disk once per change (spec-00002 §7 非功能项). */
  private body(node: DocNode): DocBody {
    let body = this.bodies.get(node.path)
    if (body === undefined) {
      body = readDocBody(this.docsDir, node)
      this.bodies.set(node.path, body)
    }
    return { id: node.id, body }
  }

  /** Every record in the repo, scanned once — the evidence set `/items` and `/coverage` share. */
  private recordScan(graph: DocGraph): RecordScan {
    this.scanned ??= scanRecords(
      graph.nodes.filter((node) => node.type === 'record').map((record) => this.body(record)),
    )
    return this.scanned
  }

  /**
   * One document's items against every record, held by document id. What is
   * shared is this derivation and the bodies under it — never which documents to
   * run it over: `/items` names one, `/coverage` takes every spec and rule that
   * is not a collision, and `graphDiagnostics` keeps its own `ok` filter. Folding
   * the three together would silently drop that filter (design-00001 §2).
   */
  private view(node: DocNode, graph: DocGraph): ItemsView {
    let view = this.views.get(node.id)
    if (view === undefined) {
      view = requirementViewFrom(this.body(node), this.recordScan(graph))
      this.views.set(node.id, view)
    }
    return view
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
    return this.view(node, graph)
  }

  /**
   * The global coverage view's payload (spec-00002-FR-10 and FR-11): every spec
   * and rule in the repo, its three counts, and its items with their state.
   *
   * What is listed is judged by `declaresItems` and `duplicateOf`, never by
   * `ok`: a document whose front matter is broken but whose body reads is in
   * (FR-10, spec-00002-AC-10.10), whatever its own status (AC-10.9), and only a
   * document colliding on its id is out — its items are claimed by nobody, so
   * ambiguous evidence stays out of the count (FR-8, spec-00002-AC-8.7). The
   * derivation is the one `/items` serves, over the same every-record evidence
   * set, so a row and the inspector cannot disagree.
   */
  coverage(): CoverageRow[] {
    const graph = this.graph()
    return graph.nodes
      .filter((node) => declaresItems(node.type) && node.duplicateOf === undefined)
      .map((node) => {
        const items = this.view(node, graph).items
        const count = (state: Coverage) => items.filter((item) => item.coverage === state).length
        return {
          docId: node.id,
          title: node.title,
          verified: count('verified'),
          failing: count('failing'),
          uncovered: count('uncovered'),
          items: items.map((item) => ({ id: item.id, coverage: item.coverage })),
        }
      })
  }

  transitions(id: string): string[] {
    return transitionsFor(this.require(id), this.config)
  }

  nextSteps(id: string): FlowStep[] {
    return nextStepsFor(this.require(id), this.config)
  }

  /** Where the document lives, relative to the docs tree: the advance instruction names it (spec-00001-FR-11). */
  pathOf(id: string): string {
    return this.require(id).path
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

  /**
   * The prefill for a new document of an entry type (spec-00001-FR-53): the
   * number rule-00001-BR-18 allocates, and that type's own template. Nothing is
   * written — the file appears only when the editor saves it (design-00001 §6).
   */
  newDocument(type: string): { idPrefix: string; template: string } {
    assertEntryType(type, this.config)
    return { idPrefix: idPrefix(type, allocateNumber(this.graph(), type)), template: this.template(type) }
  }

  /**
   * spec-00001-FR-53: the create branch of the one write path (design-00001 §6).
   * Its read-from-disk step is a non-existence check — an id already taken is a
   * conflict, never an overwrite (AC-53.3) — and the id must carry the number
   * BR-18 allocates, so nothing files a second document under a taken number.
   */
  async create(id: string, content: string): Promise<ActionResult> {
    const parsed = parseDocId(id)
    if (!parsed) {
      throw new WorkflowError(`${JSON.stringify(id)} is not <type>-<nnnnn>-<slug> with a lower-case hyphenated slug`)
    }
    assertEntryType(parsed.type, this.config)
    const graph = this.graph()
    const relPath = `${parsed.type}/${id}.md`
    const absolute = join(this.docsDir, relPath)
    // Asked of the **declared** ids, not the node keys: a colliding document is
    // keyed by its path, so `findNode` would miss it and a third file would land
    // under the same id. `existsSync` stays as the last line of defence — it only
    // knows the canonical path, and a document filed elsewhere is invisible to it
    // (design-00001 §2).
    if (graph.nodes.some((node) => declaredId(node) === id) || existsSync(absolute)) {
      throw new ConflictError(`${id} already exists; refresh the board`)
    }
    const allocated = allocateNumber(graph, parsed.type)
    if (parsed.number !== allocated) {
      const allocatedPrefix = idPrefix(parsed.type, allocated)
      throw new WorkflowError(`${id} is not the id allocated for a new ${parsed.type}; it is ${allocatedPrefix}<slug>`)
    }
    // The document is filed under the id it was asked for, so its front matter
    // has to say the same: a file whose id disagrees with its name is an
    // anomalous node the moment it lands (spec-00001-FR-2).
    if (frontMatterId(content) !== id) {
      throw new WorkflowError(`the content to save does not declare id: ${id} in its front matter`)
    }
    mkdirSync(dirname(absolute), { recursive: true })
    return this.writeFile(absolute, content, id, 'create')
  }

  /**
   * A type's TEMPLATE.md, or nothing to prefill with. A folder without one is a
   * repo missing that convention, not a reason to refuse the create: the
   * allocated id is the part the board owes the editor.
   */
  private template(type: string): string {
    try {
      return readFileSync(join(this.docsDir, type, 'TEMPLATE.md'), 'utf8')
    } catch {
      return ''
    }
  }

  /**
   * spec-00001-FR-6 and FR-7, with every gate between the ruling and the write.
   * The order is fixed (design-00001 §6): the transition table, then the
   * promotion gate, the archive gate and the resolved gate. The three are
   * mutually exclusive, so the order changes no verdict — it is fixed for the
   * cost gradient and for a message the tests can predict. All of them run on
   * the new body held in memory, before the one `writeFileSync`, which is what
   * makes a refusal leave neither a half-written file nor a commit.
   */
  async changeStatus(id: string, to: string): Promise<ActionResult> {
    const graph = this.graph()
    const node = this.require(id, graph)
    const current = this.readOrConflict(node)
    const updated = applyStatusChange(current.content, node, this.config, to)
    this.assertQuestionsResolved(node, to, current.content)
    this.assertSuperseded(node, to, graph)
    this.assertScopeVerified(node, to, graph)
    return { ...(await this.write(node, updated, 'status')), status: to }
  }

  /**
   * spec-00002-FR-1 and FR-2 with rule-00001-BR-12 (issue-00015): a draft
   * carrying unresolved open questions is not promoted out of `draft`, whichever
   * action asks for it. The reading is `workflow.hasOpenQuestions` — the very
   * function `applyAccept` calls, on the same whole-file content — so the two
   * paths cannot reach different verdicts (spec-00002-AC-1.7).
   *
   * The condition is «the target is the promoted status», not «the target is not
   * draft»: `draft → archived`, a work item's `draft → wontfix` and
   * `open → resolved`, and a living doc's `active → draft` revision round are
   * therefore none of this gate's business (spec-00002-FR-2).
   */
  private assertQuestionsResolved(node: DocNode, to: string, content: string): void {
    if (node.status !== 'draft' || to !== promotedStatus(this.config.types[node.type!]!)) return
    if (hasOpenQuestions(content)) {
      throw new WorkflowError(`${node.id} has unresolved open questions and cannot be promoted to ${to}`)
    }
  }

  /**
   * spec-00002-FR-3 and FR-4 with rule-00001-BR-19: `archived` means «replaced»,
   * so nothing reaches it until another document declares it replaced. Three
   * readings, all of them deliberate (design-00001 §2):
   *
   * - the candidates are **not** filtered by `node.ok` — the pairing reads a
   *   front matter declaration, not whether the declaring node is healthy
   *   (spec-00002-AC-4.6). A file whose front matter will not parse at all
   *   declares nothing, so it pairs with nobody; that is the same reading, not
   *   an exception to it;
   * - «another» is judged by **path**, the one key every document has to itself
   *   — a document's own `supersedes` is no pairing for itself
   *   (spec-00002-AC-4.3);
   * - neither the type nor the status of the replacement matters, and many
   *   replacements or many replaced ids are all one pairing each
   *   (spec-00002-AC-4.1, AC-4.2, AC-4.4, AC-4.5).
   */
  private assertSuperseded(node: DocNode, to: string, graph: DocGraph): void {
    if (to !== 'archived') return
    const paired = graph.nodes.some(
      (candidate) => candidate.path !== node.path && (candidate.relations.supersedes ?? []).includes(node.id),
    )
    if (!paired) {
      throw new WorkflowError(`${node.id} cannot be archived; no other document declares supersedes: ${node.id}`)
    }
  }

  /**
   * spec-00001-FR-52: a plan reaches `resolved` only once the records that name
   * it verify every item of its delivery scope. Nothing else passes this way —
   * another type, another target status (`wontfix` included), and the transition
   * is the transition table's business alone.
   */
  private assertScopeVerified(node: DocNode, to: string, graph: DocGraph): void {
    if (node.type !== 'plan' || node.status !== 'open' || to !== 'resolved') return
    // The bodies come off the shared cache; the derivation does not — the gate's
    // evidence set is narrowed to this plan's records (design-00001 §2).
    const body = (candidate: DocNode) => this.body(candidate)
    const docs = itemCoverage(
      // Colliding documents are out (spec-00002-FR-8): ambiguous evidence is no
      // evidence. The test is `duplicateOf`, never `ok` — the gate must go on
      // serving a document whose front matter is broken but whose body reads,
      // and only a collision takes it out (design-00001 §2).
      graph.nodes
        .filter((candidate) => declaresItems(candidate.type) && candidate.duplicateOf === undefined)
        .map(body),
      // Every record naming this plan its parent, whatever its own status
      // (decision-00007 §3); another plan's record is no evidence for this one.
      graph.nodes
        .filter((candidate) => candidate.type === 'record' && (candidate.relations.parent ?? []).includes(node.id))
        .map(body),
    )
    const gaps = resolvedGaps(
      node.relations.implements ?? [],
      graph.nodes.map((candidate) => candidate.id),
      docs,
    )
    if (gaps.length > 0) {
      throw new GateError(`${node.id} cannot be resolved; its delivery scope is not verified: ${gaps.join(', ')}`, gaps)
    }
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
   * What an audit session for `id` is started with (spec-00001-FR-50 and FR-51):
   * refused unless the document is a draft of an auditable type and still on disk
   * (FR-19). Audit is stateless — no progress file to read, nothing to recover
   * from — so the instruction is built from the document alone.
   */
  auditPlan(id: string): SessionPlan {
    const node = this.require(id)
    this.readOrConflict(node)
    assertAuditable(node, this.config)
    return {
      kind: 'audit',
      sourceId: node.id,
      instruction: auditInstruction({ docPath: node.path, readmePath: typeReadmePath(node.type!) }),
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

  /**
   * The node an action is addressed to. An id two documents declare is nobody's
   * key, so nothing is found — and answering «no such document» would be a lie
   * the user cannot act on. It is refused as a conflict instead
   * (spec-00002-FR-9 a): 409 is the state the request collides with, the id
   * points at no single document, and the message says which files to fix. The
   * repair is addressed by path, which is what these nodes are keyed by.
   */
  private require(id: string, graph: DocGraph = this.graph()): DocNode {
    const node = findNode(graph, id)
    if (node) return node
    const colliding = collidingPaths(graph, id)
    if (colliding.length > 0) {
      throw new ConflictError(`${id} is declared by ${colliding.join(' and ')}; fix the id collision first`)
    }
    throw new ConflictError(`${id} is not a document in this repo; refresh the board`)
  }

  private readOrConflict(node: DocNode): DocContent {
    try {
      return readDocContent(this.docsDir, node)
    } catch {
      throw new ConflictError(`${node.id} is no longer on disk; refresh the board`)
    }
  }

  private async write(node: DocNode, content: string, action: ActionKind): Promise<ActionResult> {
    return this.writeFile(join(this.docsDir, node.path), content, node.id, action)
  }

  /** The write-then-commit every action ends on; the parsed tree goes stale here. */
  private async writeFile(absolute: string, content: string, docId: string, action: ActionKind): Promise<ActionResult> {
    writeFileSync(absolute, content)
    this.invalidate()
    const repoPath = relative(this.repoRoot, absolute).split(/[\\/]/).join('/')
    return this.git.commit([repoPath], commitMessage(action, docId))
  }
}

export { contentHash }

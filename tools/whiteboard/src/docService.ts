import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, join, relative } from 'node:path'
import { ITEM_GRAMMAR } from './advance.ts'
import type { FlowConfig, FlowStep } from './config.ts'
import {
  type CowriteMaterials,
  REFERENCE_TYPE,
  type ReferenceCandidate,
  cowriteInstruction,
  guardFrontMatter,
  judgeReferences,
  materialLines,
  prefilledTemplate,
} from './cowrite.ts'
import {
  type DocContent,
  type DocGraph,
  type DocNode,
  collidingPaths,
  contentHash,
  declaredId,
  findNode,
  frontMatterId,
  frontMatterStatus,
  parseDocId,
  readDocBody,
  readDocContent,
  readGraph,
} from './docRepository.ts'
import {
  type ActionKind,
  type CommitOutcome,
  type ContentSnapshot,
  type DirtySnapshot,
  GitLayer,
  commitMessage,
} from './gitLayer.ts'
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
import { SerialQueue } from './serialQueue.ts'
import { SessionBusyError, type SessionClaim, type SessionPlan } from './sessionManager.ts'
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
  assertCowritable,
  assertEntryType,
  hasOpenQuestions,
  idPrefix,
  nextStepsFor,
  transitionsFor,
} from './workflow.ts'

/** The document changed under the action, or is gone; the caller must refresh (spec-00001-FR-5, FR-19). */
export class ConflictError extends Error {
  /**
   * `doc-missing` when the document the action names is not on disk. The session
   * entries answer their 409 with a reason (design-00001 §7), and this is the
   * third of them — the other two are the concurrency refusals. A conflict that
   * is not about a missing document carries none.
   */
  readonly reason?: 'doc-missing'

  constructor(message: string, reason?: 'doc-missing') {
    super(message)
    this.name = 'ConflictError'
    this.reason = reason
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

/** The one key of the commit queue: there is one queue, and every commit is in it. */
const COMMITS = 'commits'

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
  /**
   * The one serial queue every board commit runs in (spec-00003-FR-8,
   * design-00001 §4 and §6): the terminal session kinds' wrap-up commits and the
   * write path's own, one at a time in arrival order. One key, because there is
   * one queue — the shape is shared with the ask store, the queue is not.
   */
  private readonly queue = new SerialQueue()
  /**
   * The paths a board write put on disk and could not commit
   * (spec-00001-FR-20's retention). A cowrite's collapse filter leaves them alone
   * — restoring one would destroy the file that requirement keeps
   * (spec-00006-AC-6.6, design-00001 §11.3 (b)) — and a later commit of the same
   * path clears it, because the retention is over the moment it lands.
   */
  private readonly uncommitted = new Set<string>()
  /**
   * The cowrite session running on a document, as the front matter it was
   * admitted on (spec-00006-FR-10): present is the lock, and the two values are
   * what a save's identity has to still match. The registry is the one that
   * knows, and the board wires it in — the ruling itself belongs here, in front of
   * every write path the lock covers (design-00001 §11.4). A service nobody wired
   * it into locks nothing.
   */
  private cowriteProbe: (docId: string) => { preId: string; preStatus: string } | undefined = () => undefined

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

  /** How the write paths ask whether a document is being cowritten (spec-00006-FR-10). */
  attachCowriteProbe(probe: (docId: string) => { preId: string; preStatus: string } | undefined): void {
    this.cowriteProbe = probe
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
    this.assertIdentityKept(id, content)
    return this.write(node, content, 'edit')
  }

  /**
   * The editor bypass, closed (spec-00006-FR-10, design-00001 §11.4): a
   * whole-file overwrite could move the front matter `id` or `status` of a
   * document a cowrite session is writing, which is exactly what the status lock
   * refuses on its own paths. A body-only save is the turn-taking the round is
   * about and goes through untouched (spec-00006-AC-10.4).
   *
   * The comparison is against the session's **fixed** `preId` and `preStatus`,
   * never against the file on disk: the agent moves that mid-session, the clean
   * buffer reloads what it moved, and a body-only save over the reloaded text
   * would then carry the moved status past this guard and land it — the document
   * promoted by nobody. The two values the session was admitted on do not move,
   * so they are the only honest reference (spec-00006-AC-10.3).
   */
  private assertIdentityKept(id: string, content: string): void {
    const admitted = this.cowriteProbe(id)
    if (!admitted) return
    const moved = frontMatterId(content) !== admitted.preId || frontMatterStatus(content) !== admitted.preStatus
    if (moved) {
      throw new SessionBusyError(
        `${id} has a running cowrite session, so a save may not change its front matter id or status`,
        'doc-busy',
      )
    }
  }

  /**
   * The status lock (spec-00006-FR-10): while a cowrite session is running on a
   * document, the actions that would promote it or rewrite its identity are
   * refused — 409, because it is the document's current state the request
   * collides with, and the same reason word the start refusals carry
   * (design-00001 §11.4). Judged before the ruling chain, so nothing is even
   * evaluated on a document that is being written.
   */
  private assertNotCowriting(id: string, action: string): void {
    if (this.cowriteProbe(id) === undefined) return
    throw new SessionBusyError(
      `${id} has a running cowrite session, so it takes no ${action} until it ends`,
      'doc-busy',
    )
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
    this.assertNotCowriting(id, 'status change')
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
    this.assertNotCowriting(id, 'review action')
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

  /**
   * What one ask call is started with (spec-00005-FR-1 and FR-2): any status,
   * any sound type — an anomalous document is refused, as the terminal form
   * refused it. The payload is the whole argv the headless call carries
   * (design-00001 §10.1): a thread's **first** call gets the read-only
   * instruction with its context paths and the question after it; a follow-up
   * gets the question alone, because the conversation it resumes was already
   * told all of that and would be paying for it twice.
   */
  askPlan(id: string, question: string, thread: { id: string; resumeId?: string }): SessionPlan {
    const graph = this.graph()
    const node = this.require(id, graph)
    this.readOrConflict(node)
    assertAskable(node)
    const instruction = askInstruction({ docPath: node.path, relatedPaths: relatedDocPaths(graph, node.id) })
    return {
      kind: 'ask',
      sourceId: node.id,
      threadId: thread.id,
      resumeId: thread.resumeId,
      instruction: thread.resumeId === undefined ? `${instruction}\n\n${question}` : question,
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
   * What a cowrite session for `id` is started with (spec-00006-FR-1 and FR-9):
   * refused unless the document is still on disk (FR-19, spec-00001-AC-19.3) and
   * its status is one cowrite may be started on (rule-00001-BR-29). Every type is
   * eligible — cowrite writes a body, and every type has one.
   *
   * `reservedReferences` are the reference numbers running sessions already hold
   * (spec-00003-FR-1): the instruction's starting number counts them as taken,
   * the same reading the collapse filter takes of them (design-00001 §11.3).
   */
  cowritePlan(id: string, materials?: CowriteMaterials, reservedReferences: readonly number[] = []): SessionPlan {
    const graph = this.graph()
    const node = this.require(id, graph)
    this.readOrConflict(node)
    assertCowritable(node, this.config)
    return this.cowriteSessionPlan(
      { docId: node.id, path: node.path, type: node.type!, status: node.status! },
      materials,
      reservedReferences,
    )
  }

  /**
   * What a cowrite session that files its own target is started with
   * (spec-00006-FR-2): the three create rejections of spec-00001-FR-53 judged
   * here and now — the type is a flow entry type, the slug is a slug, the id is
   * free — and the plan built from values that are not on disk yet. Nothing is
   * written: the file comes after the slot is taken (design-00001 §11.2), and a
   * refusal here has taken nothing to give back (spec-00006-AC-2.2 … AC-2.4).
   */
  cowriteCreatePlan(
    type: string,
    slug: string,
    materials?: CowriteMaterials,
    reservedReferences: readonly number[] = [],
  ): { plan: SessionPlan; docId: string; path: string } {
    const { id, path } = this.newCowriteDoc(type, slug)
    // A `reference` target has taken a reference number of its own, and nothing is
    // on disk yet: read off the graph alone, the instruction's first free number
    // would be the target's own, and the session's first document would collide
    // with the document it is writing (rule-00001-BR-18, spec-00006-FR-2).
    const reserved =
      type === REFERENCE_TYPE ? [...reservedReferences, parseDocId(id)!.number] : reservedReferences
    return {
      docId: id,
      path,
      // A new document is `draft` by rule-00001-BR-26, which is what the guard of
      // the collapse filter will hold its front matter to.
      plan: this.cowriteSessionPlan({ docId: id, path, type, status: 'draft' }, materials, reserved),
    }
  }

  /**
   * File the document a create-form cowrite is about to be started on
   * (spec-00006-FR-2): that type's `TEMPLATE.md` with the front matter prefilled,
   * written and committed as a create — the same commit `POST /api/docs` makes,
   * because it is the same act with the editor left out.
   *
   * The target is the one `cowriteCreatePlan` already worked out, threaded here
   * rather than allocated a second time: two allocations off the same graph is a
   * number read twice where one document is being filed, and the plan the session
   * was admitted on is the one that must land. What is judged again is the thing
   * the prefill could get wrong — the front matter has to declare the very id the
   * file is named for, the reading `create` takes of a save (spec-00001-FR-2): a
   * template whose `id` line the fill missed is an anomalous document the moment
   * it lands.
   *
   * A write that fails leaves no file behind — the create is whole or nothing —
   * while a **commit** that fails keeps the file and reports the error, which is
   * the retention spec-00001-FR-20 already fixes: the document is on disk, so the
   * session can be cowritten on it.
   */
  async createForCowrite(target: { id: string; path: string; type: string }): Promise<ActionResult> {
    const { id, path, type } = target
    const absolute = join(this.docsDir, path)
    mkdirSync(dirname(absolute), { recursive: true })
    try {
      const content = prefilledTemplate(this.template(type), id, type)
      if (frontMatterId(content) !== id) {
        throw new WorkflowError(`the prefilled ${type} template does not declare id: ${id} in its front matter`)
      }
      return await this.writeFile(absolute, content, id, 'create')
    } catch (cause) {
      // Half a file is an orphan document, and FR-2 promises none: the disk goes
      // back to having no such document at all.
      rmSync(absolute, { force: true })
      throw cause
    }
  }

  /**
   * The id a create-form cowrite would file, and the path it would file it at —
   * or the refusal that stops it (spec-00006-FR-2 with spec-00001-FR-53). The
   * number is allocated here rather than given, so the id can only collide with a
   * file the graph could not read as a document; both readings are taken all the
   * same, for the reason design-00001 §2 gives — a colliding document is keyed by
   * its path, and a document filed away from its canonical path is invisible to
   * `existsSync`.
   */
  private newCowriteDoc(type: string, slug: string): { id: string; path: string } {
    assertEntryType(type, this.config)
    const graph = this.graph()
    const id = `${idPrefix(type, allocateNumber(graph, type))}${slug}`
    if (!parseDocId(id)) {
      throw new WorkflowError(`${JSON.stringify(slug)} is not a lower-case hyphenated slug, so ${id} is not an id`)
    }
    const relPath = `${type}/${id}.md`
    if (graph.nodes.some((node) => declaredId(node) === id) || existsSync(join(this.docsDir, relPath))) {
      throw new ConflictError(`${id} already exists; refresh the board`)
    }
    return { id, path: relPath }
  }

  /** The plan both cowrite forms share; the target is described rather than looked up. */
  private cowriteSessionPlan(
    target: { docId: string; path: string; type: string; status: string },
    materials: CowriteMaterials | undefined,
    reservedReferences: readonly number[],
  ): SessionPlan {
    return {
      kind: 'cowrite',
      sourceId: target.docId,
      instruction: cowriteInstruction({
        docPath: target.path,
        docType: target.type,
        readmePath: typeReadmePath(target.type),
        grammar: ITEM_GRAMMAR[target.type],
        referenceStart: allocateNumber(this.graph(), REFERENCE_TYPE, reservedReferences),
        materialLines: materialLines(materials, this.graph()),
      }),
      cowrite: { targetPath: target.path, preId: target.docId, preStatus: target.status },
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
   * The whole text of that same dirt, for a cowrite session alone
   * (design-00001 §11.3): its collapse filter restores what it filters, and a
   * digest cannot be written back.
   */
  contentSnapshotDocs(): ContentSnapshot {
    return this.git.contentSnapshot(this.docsPath())
  }

  /**
   * Commit what an agent session left under docs/ (spec-00001-FR-14): the content
   * that moved since `before`, never the dirt the session inherited. Nothing
   * moved, nothing committed (spec-00001-AC-14.6). `action` is the session's kind
   * — the commit says which of the three it was (spec-00001-AC-14.8) —
   * and defaults to the advance every existing caller means.
   */
  async commitSessionChanges(
    docId: string,
    before: DirtySnapshot,
    action: ActionKind = 'advance',
  ): Promise<ActionResult> {
    // The difference is taken inside the turn, never before it: read outside,
    // it would be a reading of a tree another commit in the queue is still
    // moving — and two sessions ending at once would each stage what the other
    // has just had committed (spec-00003-AC-8.1, AC-8.5).
    return this.serially(async () => {
      const paths = await this.git.changedSince(this.docsPath(), before)
      const outcome = await this.git.commit(paths, commitMessage(action, docId))
      this.forget(paths, outcome)
      return outcome
    })
  }

  /**
   * The retention of spec-00001-FR-20 is over the moment the path lands
   * (design-00001 §11.3 (b)): **any** commit that staged it clears it — a session's
   * as much as the write path's own — or the set would go on protecting a path
   * that is committed, and a later cowrite would leave a stray write of it in the
   * working tree for good.
   */
  private forget(paths: readonly string[], outcome: CommitOutcome): void {
    if (!outcome.committed) return
    for (const path of paths) this.uncommitted.delete(path)
  }

  /**
   * The collapse of a cowrite session (spec-00006-FR-6 and FR-8,
   * rule-00001-BR-30's enforcement layer, design-00001 §11.3): of everything that
   * moved under docs/ since this session's own snapshot, the target document and
   * the well-formed new references are staged and committed, and the rest is put
   * back. Filter, staging and commit are **one turn of the commit queue** — the
   * user actions ahead of it in the queue have committed already, so their writes
   * are clean paths by the time this reads them and are never mistaken for the
   * session's (spec-00006-FR-6's timing).
   *
   * `claims` are what the still-running sessions may write, and
   * `reservedReferences` the numbers they hold; both are readings of the registry
   * the caller alone can take. Nothing staged, nothing committed
   * (spec-00006-AC-8.2). The filter is irreversible and runs before the commit: a
   * commit that then fails leaves the in-scope changes in the working tree, which
   * is spec-00001-FR-20's retention, and the out-of-scope evidence is already gone
   * (design-00001 §11.3 边界声明).
   */
  async commitCowriteChanges(
    plan: SessionPlan,
    before: DirtySnapshot,
    claims: readonly SessionClaim[] = [],
    reservedReferences: readonly number[] = [],
  ): Promise<{ committed: boolean; error?: string; problems: string[] }> {
    const cowrite = plan.cowrite!
    return this.serially(async () => {
      const docsPath = this.docsPath()
      const paths = await this.movedPaths(docsPath, before, plan.contentBaseline)
      const problems: string[] = []
      const staged: string[] = []
      const targetPath = `${docsPath}/${cowrite.targetPath}`
      if (paths.includes(targetPath)) {
        // Before the fresh read below, so what that read sees of the target is
        // the front matter the guard leaves (design-00001 §11.3 步骤 1).
        this.collapseTarget(targetPath, cowrite, staged, problems)
      }
      // Read again rather than off the cache: the watcher's debounce window may
      // not have invalidated it yet, and every judgment below is of the tree as
      // it stands at the collapse (design-00001 §11.3 撞 id 判定的三个读法).
      this.invalidate()
      const graph = this.graph()
      // The two exemptions, judged **before** the classification and not after
      // it (design-00001 §11.3 (3)): another running session's product is that
      // session's to commit (spec-00006-AC-6.5), and a path the write path could
      // not commit is left where spec-00001-FR-20 keeps it (spec-00006-AC-6.6).
      // Read the other way round, a concurrent session's brand-new reference
      // would be classed as this session's candidate — staged into the wrong
      // commit, or deleted from under a session that is still writing it.
      const exempt = this.exemptions(docsPath, claims, graph)
      const candidates = paths
        .filter((path) => path !== targetPath && !exempt(path) && this.isNewReference(path, docsPath, before))
        .map((path) => ({ path, node: this.nodeAt(graph, path, docsPath) }))
      this.collapseReferences(candidates, graph, reservedReferences, staged, problems)
      const own = new Set(candidates.map((candidate) => candidate.path))
      for (const path of paths) {
        if (path === targetPath || own.has(path) || exempt(path)) continue
        this.restore(path, plan.contentBaseline, problems)
      }
      problems.push(...this.productProblems(graph, staged, docsPath))
      const outcome = await this.git.commit(staged, commitMessage('cowrite', plan.sourceId))
      this.forget(staged, outcome)
      return { ...outcome, problems }
    })
  }

  /**
   * Every path the filter has to walk (design-00001 §11.3): what git calls dirty
   * against the session's own snapshot, **and** what the session put back to a
   * content git no longer calls dirty at all.
   *
   * The second half is not a refinement. A file that was already dirty when the
   * session started and that the agent reverted to its HEAD content is clean by
   * every git reading — so `changedSince` never names it — while the owner's
   * unsaved edits to it are gone. The content baseline is the only place they
   * still exist, and a path whose text differs from what that baseline recorded is
   * a path this filter owes a restore (spec-00006-FR-6).
   */
  private async movedPaths(
    docsPath: string,
    before: DirtySnapshot,
    baseline: ContentSnapshot | undefined,
  ): Promise<string[]> {
    const changed = await this.git.changedSince(docsPath, before)
    if (baseline === undefined) return changed
    const seen = new Set(changed)
    const reverted = [...baseline.keys()].filter(
      (path) => !seen.has(path) && this.git.currentText(path) !== baseline.get(path),
    )
    return [...changed, ...reverted]
  }

  /**
   * The target document (design-00001 §11.3 步骤 1): staged, its front matter
   * `id` and `status` put back to what they were when the session started — the
   * body it wrote stays (spec-00006-AC-6.4, rule-00001-AC-30.5). A target that is
   * no longer on disk is the one case nothing is staged for: a deletion is no
   * landed write BR-30 authorises, the working tree is left as it is, and the
   * situation is a finding for the diagnostics to carry (spec-00006-AC-6.7).
   */
  private collapseTarget(
    targetPath: string,
    cowrite: NonNullable<SessionPlan['cowrite']>,
    staged: string[],
    problems: string[],
  ): void {
    const absolute = join(this.docsDir, cowrite.targetPath)
    if (!existsSync(absolute)) {
      problems.push(`${cowrite.targetPath} is no longer on disk, so its deletion was not staged`)
      return
    }
    const guarded = guardFrontMatter(readFileSync(absolute, 'utf8'), cowrite.preId, cowrite.preStatus)
    writeFileSync(absolute, guarded.content)
    if (guarded.problem !== undefined) problems.push(`${cowrite.targetPath}: ${guarded.problem}`)
    staged.push(targetPath)
  }

  /** The new references, judged as one set and then staged or deleted (spec-00006-FR-6). */
  private collapseReferences(
    candidates: readonly ReferenceCandidate[],
    graph: DocGraph,
    reservedReferences: readonly number[],
    staged: string[],
    problems: string[],
  ): void {
    const own = new Set(candidates.map((candidate) => candidate.node?.path))
    const others = graph.nodes.filter((node) => !own.has(node.path))
    const highest = Math.max(
      0,
      ...others
        .map((node) => parseDocId(declaredId(node)))
        .flatMap((parsed) => (parsed?.type === REFERENCE_TYPE ? [parsed.number] : [])),
      ...reservedReferences,
    )
    const verdict = judgeReferences(candidates, new Set(others.map((node) => declaredId(node))), highest)
    staged.push(...verdict.wellFormed)
    for (const { path, reason } of verdict.rejected) {
      // It was not there when the session started, so deleting it *is* the
      // restore (design-00001 §11.3 步骤 2).
      rmSync(join(this.repoRoot, path), { force: true })
      problems.push(`${path} did not land: ${reason}`)
    }
  }

  /**
   * Whether a path is out of this filter's hands (spec-00006-AC-6.5, AC-6.6): it
   * is claimed by a session that is still running, or the write path is holding
   * it under spec-00001-FR-20's retention.
   *
   * A claim is what the other session **may** write, worked out from the registry
   * (design-00001 §11.3), and never «everything that moved since its snapshot»:
   * that reading is a reading of the disk, and the disk holds this session's own
   * strays too — so one concurrent session would exempt them all and switch the
   * whole filter off. Per kind:
   *
   * - every kind claims the file of the document it is about, which is the one
   *   thing all four of them write;
   * - an advance claims its product, wherever under its target type's folder it
   *   files it, by the id prefix its expectation fixed (spec-00003-FR-1);
   * - another cowrite claims its own target, and any path under `reference/` that
   *   its **own** baseline did not hold — a reference it created since it started,
   *   which nothing else can tell from one of this session's.
   *
   * This session's own strays are claimed by nobody, which is the whole point:
   * they are restored.
   */
  private exemptions(
    docsPath: string,
    claims: readonly SessionClaim[],
    graph: DocGraph,
  ): (path: string) => boolean {
    const claimed = new Set<string>()
    /** The claims that are a rule over a folder rather than a path (advance, cowrite). */
    const rules: Array<(path: string) => boolean> = []
    for (const claim of claims) {
      const source = findNode(graph, claim.sourceId)
      if (source) claimed.add(`${docsPath}/${source.path}`)
      if (claim.targetPath !== undefined) claimed.add(`${docsPath}/${claim.targetPath}`)
      if (claim.targetType !== undefined && claim.idPrefix !== undefined) {
        const folder = `${docsPath}/${claim.targetType}/`
        const { idPrefix: prefix } = claim
        rules.push((path) => path.startsWith(folder) && path.slice(folder.length).startsWith(prefix))
      }
      if (claim.kind === 'cowrite') {
        const folder = `${docsPath}/${REFERENCE_TYPE}/`
        const { baseline } = claim
        rules.push((path) => path.startsWith(folder) && !baseline.has(path))
      }
    }
    return (path) => claimed.has(path) || rules.some((rule) => rule(path)) || this.uncommitted.has(path)
  }

  /**
   * A path this session created under `docs/reference/`: not in the dirt it
   * inherited, and not in HEAD either. A reference the session **rewrote** is no
   * new document and takes the ordinary out-of-scope treatment — the second birth
   * path of rule-00001-BR-26 is a birth, not a licence over the folder.
   */
  private isNewReference(path: string, docsPath: string, before: DirtySnapshot): boolean {
    return path.startsWith(`${docsPath}/${REFERENCE_TYPE}/`) && !before.has(path) && !this.git.inHead(path)
  }

  /** The node the fresh read made of that repo-relative path, if it made one. */
  private nodeAt(graph: DocGraph, path: string, docsPath: string): DocNode | undefined {
    const relPath = path.slice(`${docsPath}/`.length)
    return graph.nodes.find((node) => node.path === relPath)
  }

  /**
   * Put a path back the way design-00001 §11.3 (3) sets out: the snapshot's text
   * if it held any, its absence if it recorded a deletion, HEAD if the path was
   * clean when the session started — and, when HEAD does not carry it either, by
   * deleting the file the session created out of scope. None of this is committed.
   *
   * A restore that fails is reported and nothing more: the file is left as the
   * session wrote it, which is the honest outcome — a filter that cannot put a
   * path back must not go on to destroy it, and the whole collapse must not fall
   * over on one path (spec-00006-FR-8's reporting).
   */
  private restore(path: string, baseline: ContentSnapshot | undefined, problems: string[]): void {
    try {
      if (baseline?.has(path) === true) this.git.restoreContent(path, baseline.get(path)!)
      else this.git.restoreFromHead(path)
    } catch (cause) {
      problems.push(`${path} could not be put back: ${(cause as Error).message}`)
    }
  }

  /**
   * The product validation of spec-00006-FR-8 (spec-00001-FR-17's reading): the
   * front matter and item-grammar findings of what is being committed, reported
   * and blocking nothing (spec-00001-FR-40).
   */
  private productProblems(graph: DocGraph, staged: readonly string[], docsPath: string): string[] {
    return staged.flatMap((path) => {
      const node = this.nodeAt(graph, path, docsPath)
      if (!node) return []
      return [
        ...node.problems.map((problem) => `${node.path}: ${problem}`),
        ...graph.diagnostics
          .filter((diagnostic) => diagnostic.docId === node.id)
          .map((diagnostic) => `${node.path}: ${diagnostic.kind} at line ${diagnostic.line ?? 0}`),
      ]
    })
  }

  /**
   * Run one commit's whole turn — work out what to stage, write if the turn
   * writes, stage, commit — with no other board commit in flight
   * (spec-00003-FR-8).
   */
  private serially<T>(turn: () => Promise<T>): Promise<T> {
    return this.queue.run(COMMITS, turn)
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
    throw new ConflictError(`${id} is not a document in this repo; refresh the board`, 'doc-missing')
  }

  private readOrConflict(node: DocNode): DocContent {
    try {
      return readDocContent(this.docsDir, node)
    } catch {
      throw new ConflictError(`${node.id} is no longer on disk; refresh the board`, 'doc-missing')
    }
  }

  private async write(node: DocNode, content: string, action: ActionKind): Promise<ActionResult> {
    return this.writeFile(join(this.docsDir, node.path), content, node.id, action)
  }

  /**
   * The write-then-commit every action ends on; the parsed tree goes stale here.
   * Both halves are one turn of the commit queue (spec-00003-AC-8.4): a session
   * wrapping up in the meantime then either sees this file before it is written
   * or after it is committed, so neither commit can stage the other's change and
   * neither swallows the other.
   */
  private async writeFile(absolute: string, content: string, docId: string, action: ActionKind): Promise<ActionResult> {
    return this.serially(async () => {
      writeFileSync(absolute, content)
      this.invalidate()
      const repoPath = relative(this.repoRoot, absolute).split(/[\\/]/).join('/')
      const outcome = await this.git.commit([repoPath], commitMessage(action, docId))
      // The file is kept whatever git said (spec-00001-FR-20), and a cowrite
      // collapse must not restore it away — so a write with no commit behind it
      // is remembered until one lands (design-00001 §11.3 (b)).
      if (outcome.committed) this.uncommitted.delete(repoPath)
      else this.uncommitted.add(repoPath)
      return outcome
    })
  }
}

export { contentHash }

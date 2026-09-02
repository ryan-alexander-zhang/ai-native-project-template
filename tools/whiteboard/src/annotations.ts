import { type AnchorLocation, type SelectionAnchor, relocate } from './annotationAnchor.ts'
import {
  type Annotation,
  type AnnotationBatch,
  type AnnotationChange,
  AnnotationConflictError,
  type AnnotationInput,
  type AnnotationList,
  AnnotationStore,
  type AnnotationType,
  type BatchStatus,
  type BlockedReason,
} from './annotationStore.ts'
import type { AgentConfig } from './config.ts'
import { REFERENCE_TYPE, issueMaterialLines } from './cowrite.ts'
import type { AnnotationTarget, DocService } from './docService.ts'
import { unrunnable } from './pty.ts'
import { SessionBusyError, type SessionInfo, type SessionManager } from './sessionManager.ts'
import { WorkflowError } from './workflow.ts'

/**
 * An annotation request the contract refuses in a word (design-00001 §12.3).
 * A `WorkflowError`, so it answers 422 like every other refused action, and the
 * word rides in the body beside the message — the front end picks its wording
 * off the word and never off the prose.
 */
export class AnnotationError extends WorkflowError {
  readonly reason: 'type-ineligible' | 'empty-text' | 'doc-anomalous' | 'unsaved-buffer' | 'empty-submit' | AgentRefusal

  constructor(message: string, reason: AnnotationError['reason']) {
    super(message)
    this.name = 'AnnotationError'
    this.reason = reason
  }
}

/** The two ways the agent a submit names is no agent for that path (design-00001 §12.3). */
type AgentRefusal = 'unknown-agent' | 'agent-not-headless'

/** One annotation as the list serves it: the record, and where its anchor lands now. */
export interface AnnotationView extends Annotation {
  locate: AnchorLocation
}

/**
 * What the submit entry says will happen (spec-00007-FR-5, design-00001 §12.3),
 * served with the list rather than from an entry of its own — the statement and
 * the list are two halves of one rendering, and computing the gates twice is two
 * implementations that will drift.
 *
 * The counts are the unsubmitted region's, by type, and they **do not** discount
 * orphans: an anchor is only read at the moment of the submit, so counting them
 * here would move a whole-file scan into every refresh and still disagree with
 * what the submit finds. The two gates carry the status ruling and the agent
 * declaration and **nothing else** — neither same-document exclusion nor the
 * session cap, which spec-00007 §1 expressly excludes the submit entry from and
 * which are judged per path at the submit instead.
 */
export interface SubmitPreview {
  questions: number
  issues: number
  willTransitionTo: 'draft' | null
  issueEligible: boolean
  questionEligible: boolean
}

/** The whole of `GET /api/annotations/:id` (design-00001 §12.3). */
export interface AnnotationListView {
  annotations: AnnotationView[]
  batches: AnnotationBatch[]
  submitPreview: SubmitPreview
}

/** One annotation a submit held back, and why (design-00001 §12.3). */
export interface BlockedAnnotation {
  annotationId: string
  reason: BlockedReason
  message: string
}

/** The transition a submit made on its way to the cowrite (spec-00007-FR-7). */
export interface SubmitTransition {
  to: string
  committed: boolean
  /** Why the commit did not land, if it did not: the file stays as it is (spec-00001-FR-20). */
  error?: string
}

/**
 * What a submit did (design-00001 §12.3). The status code draws one line and
 * this payload the other: **4xx means nothing at all happened**, while **200
 * means the batch ran and every per-annotation outcome is in here** — an anchor
 * that no longer lands, a path that lost its eligibility, a cap that was reached
 * are single-annotation or single-path outcomes, and saying them in a status code
 * would have the front end guessing which ones went.
 */
export interface SubmitResult {
  submitted: {
    questions: Array<{ annotationId: string; threadId: string; sessionId: string }>
    issues: { batchId: string; sessionId: string; annotationIds: string[] } | null
  }
  blocked: BlockedAnnotation[]
  transition: SubmitTransition | null
  /**
   * What went ahead but could not be written down — a thread that was opened and
   * whose reference the store refused. Present only when there is one: the
   * dispatch happened, so it is no refusal, and saying nothing would leave the
   * owner with a thread nothing points at.
   */
  warnings?: string[]
}

/** How a unified submit opens one ask thread — the very receipt chain a typed question runs. */
export type OpenAskThread = (input: {
  docId: string
  question: string
  agent: string
  selection: SelectionAnchor
}) => Promise<{ sessionId: string; threadId: string }>

export interface AnnotationOptions {
  repoRoot: string
  docs: DocService
  sessions: SessionManager
  /** Every agent the flow config declares: the two paths choose from it, each from its own subset. */
  agents: AgentConfig[]
  openAsk: OpenAskThread
}

/**
 * The annotations of the documents on this board (spec-00007): the store beneath,
 * the gates in front of it, and the unified submit that hands one document's
 * annotations to the two paths that already exist — questions to the headless ask
 * of §10, issues to the cowrite of §11. Nothing here is a new kind of session:
 * every dispatch below calls the same function a hand-started one calls, which is
 * how «no difference in behaviour» (spec-00007-FR-8) holds mechanically.
 */
export class Annotations {
  readonly store: AnnotationStore
  private readonly options: AnnotationOptions
  /**
   * The documents whose submit is in flight (spec-00007-FR-10, design-00001
   * §12.3). In memory rather than on disk: being in flight lasts one request, so
   * a restart has nothing to recover — the unsubmitted region is still there and
   * the submit can simply be made again. It is **not** the store's per-document
   * write queue: that one serialises a single read-modify-write, while this spans
   * a transition, a spawn and several writes.
   */
  private readonly inFlight = new Set<string>()
  /**
   * The landings that could not be written, by document (design-00001 §12.6). A
   * batch whose file was unreadable when its session ended would otherwise read
   * `cowriting` for good, with its annotations submitted and no way out until a
   * restart; kept here, the next write of that document retries it, and the
   * startup pass is the backstop under that.
   */
  private readonly unlanded = new Map<string, Landing[]>()

  constructor(options: AnnotationOptions) {
    this.options = options
    this.store = new AnnotationStore(options.repoRoot)
  }

  /**
   * A document's annotations, each with where its anchor lands on the disk as it
   * stands, and the submit statement (design-00001 §12.3). The reading is taken
   * fresh every time and nothing is cached: one whole-file scan per annotation
   * over a repo of tens of kilobytes is nothing next to a cache that would have
   * to be invalidated with the disk.
   *
   * A document that is gone or renamed still answers — its annotations are kept
   * (spec-00007-FR-11) — with nothing to locate against and both gates shut,
   * because there is no document left to annotate.
   */
  list(docId: string): AnnotationListView {
    const list = this.store.read(docId)
    const target = this.targetOrNone(docId)
    const source = target === undefined ? undefined : this.sourceOrNone(docId)
    return {
      annotations: list.annotations.map((annotation) => ({
        ...annotation,
        locate: source === undefined ? { failed: 'missing' } : relocate(source, annotation.anchor),
      })),
      batches: list.batches,
      submitPreview: this.preview(list, target),
    }
  }

  /**
   * One new annotation (spec-00007-FR-1): the gates first, then the record. The
   * anchor is taken as given — it may have been cut from an unsaved buffer, which
   * is a text only the editor holds (spec-00007-AC-1.3) — while the quote is
   * derived from it by the store.
   */
  async add(docId: string, body: unknown): Promise<Annotation> {
    const input = annotationInput(body)
    this.gate(docId, input)
    await this.retryLandings(docId)
    return this.store.add(docId, input)
  }

  /**
   * Change an annotation before it goes (spec-00007-FR-3): its text, its type, or
   * its selection — the last being the way out of an orphaned annotation
   * (spec-00007-AC-3.4). What it sets is what is judged: a type it moves to goes
   * through the same gate an added one does, and a text it sets may not be empty.
   */
  async change(docId: string, annotationId: string, body: unknown): Promise<Annotation> {
    const change = annotationChange(body)
    this.assertNotSubmitting(docId)
    this.gate(docId, change)
    await this.retryLandings(docId)
    return this.store.patch(docId, annotationId, change)
  }

  /**
   * Drop an annotation before it goes (spec-00007-FR-3). The document itself is
   * not consulted: an annotation of a document that has been deleted or renamed
   * is board state its owner may still clear away.
   */
  async remove(docId: string, annotationId: string): Promise<void> {
    this.assertNotSubmitting(docId)
    await this.retryLandings(docId)
    await this.store.remove(docId, annotationId)
  }

  /**
   * While a submit of that document is in flight, its unsubmitted annotations are
   * frozen (spec-00007-FR-10's in-flight refusal, design-00001 §12.3). The submit
   * reads the set it is dispatching **once** and then spends several awaits
   * transitioning, admitting and spawning: a change or a delete landing in that
   * window would have the batch and the materials built from a text or a
   * selection that is already gone, and a deleted annotation would leave the
   * batch claiming something that is not there.
   *
   * The freeze is the whole of the fix rather than marking the set `submitted`
   * up front: an annotation the submit then holds back (an orphan, a lost
   * eligibility, a full cap) must stay `pending` throughout, and `state` is the
   * one criterion the change and delete entries read (design-00001 §12.3).
   * Adding is left open — a new annotation is not in this submit's set, so it
   * cannot be built from a stale reading of anything.
   */
  private assertNotSubmitting(docId: string): void {
    if (!this.inFlight.has(docId)) return
    throw new AnnotationConflictError(
      `a unified submit of ${docId} is in flight, so its annotations take no change until it is done`,
      'submit-in-flight',
    )
  }

  /**
   * A unified submit (spec-00007-FR-5). The whole-batch preconditions come first
   * and in the fixed order of design-00001 §12.3 — in flight, the document
   * exists, the document is sound, the buffer is saved, there is something to
   * submit, the named agents are agents — and any one of them means **nothing at
   * all happens**. The order runs by cost: the second request must not touch the
   * disk, and telling the owner of a deleted or broken document to «save first»
   * would be the wrong sentence.
   */
  async submit(docId: string, body: unknown): Promise<SubmitResult> {
    const request = submitRequest(body)
    if (this.inFlight.has(docId)) {
      throw new AnnotationConflictError(
        `a unified submit of ${docId} is already in flight; nothing was submitted twice`,
        'submit-in-flight',
      )
    }
    // Read again rather than off the cache, the way the collapse filter does
    // (§11.3): a document deleted or renamed since the last refresh has to be
    // seen as such here (spec-00007-AC-10.6), and the issue gate is a reading of
    // the front matter as it is on disk now (design-00001 §12.3).
    const target = this.freshTarget(docId)
    if (!target.sound) {
      throw new AnnotationError(`${docId} has front matter problems, so it takes no annotation`, 'doc-anomalous')
    }
    if (request.unsavedChanges) {
      throw new AnnotationError(
        `save the editor buffer of ${docId} before submitting its annotations`,
        'unsaved-buffer',
      )
    }
    const pending = this.store.read(docId).annotations.filter((annotation) => annotation.state === 'pending')
    if (pending.length === 0) {
      throw new AnnotationError(`${docId} has no unsubmitted annotation to submit`, 'empty-submit')
    }
    const agents = this.chooseAgents(request.agents)
    // Nothing above this line has awaited, so the reading and the claim are one
    // step: two requests cannot both pass the check (design-00001 §12.3).
    this.inFlight.add(docId)
    try {
      await this.retryLandings(docId)
      return await this.dispatch(docId, target, pending, agents)
    } finally {
      this.inFlight.delete(docId)
    }
  }

  /**
   * Land a finished session on the batch it was started for (design-00001 §12.6):
   * the registry's end state mapped to the batch's, and the collapse commit as
   * the reference the list shows (spec-00007-AC-9.4, AC-9.5). Anything but a
   * natural end hands the annotations back to the unsubmitted region.
   *
   * Every session comes through here and nearly none of them is a batch, which
   * the store answers by finding nothing to write.
   *
   * A write that fails is **raised**, so the caller records it and the owner is
   * told — and it is kept for the next write of that document to retry, because a
   * batch nobody could land reads `cowriting` for ever otherwise.
   */
  async landBatch(info: SessionInfo): Promise<void> {
    const status = BATCH_END[info.status]
    if (info.kind !== 'cowrite' || status === undefined) return
    const landing: Landing = { sessionId: info.id, status, commit: info.outcome?.sha ?? null }
    try {
      await this.store.landBatch(info.sourceId, landing.sessionId, landing.status, landing.commit)
    } catch (cause) {
      this.keepLanding(info.sourceId, landing)
      throw cause
    }
  }

  /**
   * The landings a broken file left behind, tried again now that this document is
   * being written anyway (design-00001 §12.6). One that fails again is put back
   * and the rest wait with it: the order they ended in is the order they land in.
   */
  private async retryLandings(docId: string): Promise<void> {
    const waiting = this.unlanded.get(docId)
    if (waiting === undefined) return
    this.unlanded.delete(docId)
    for (const [index, landing] of waiting.entries()) {
      try {
        await this.store.landBatch(docId, landing.sessionId, landing.status, landing.commit)
      } catch {
        for (const left of waiting.slice(index)) this.keepLanding(docId, left)
        return
      }
    }
  }

  private keepLanding(docId: string, landing: Landing): void {
    this.unlanded.set(docId, [...(this.unlanded.get(docId) ?? []), landing])
  }

  /**
   * The two paths, in the order spec-00007-FR-5 promises they can be observed in:
   * the cowrite whole — gate, judgment, precheck, transition, slot, spawn — and
   * only then the questions, one after another. An `await` sequence rather than
   * anything concurrent, which is what makes «the last slot goes to the issues»
   * true with no reservation mechanism at all (spec-00007-AC-5.8).
   */
  private async dispatch(
    docId: string,
    target: AnnotationTarget,
    pending: readonly Annotation[],
    agents: ChosenAgents,
  ): Promise<SubmitResult> {
    const blocked: BlockedAnnotation[] = []
    const warnings: string[] = []
    const ready = await this.verifyAnchors(docId, pending, blocked)
    const issues = ready.filter((annotation) => annotation.type === 'issue')
    const questions = ready.filter((annotation) => annotation.type === 'question')
    const started = issues.length === 0 ? {} : await this.startIssues(docId, target, issues, agents.cowrite, blocked)
    const submitted = await this.submitQuestions(docId, questions, agents.question, { blocked, warnings })
    return {
      submitted: { questions: submitted, issues: started.issues ?? null },
      blocked,
      transition: started.transition ?? null,
      ...(warnings.length === 0 ? {} : { warnings }),
    }
  }

  /**
   * Every annotation's anchor read against the disk once, before anything is
   * dispatched (spec-00007-FR-5). One that no longer lands is marked where it
   * stands and enters no path at all — a single annotation held back, never the
   * batch — while one that lands again has its mark cleared. The record of the
   * last submit's refusal is cleared on every annotation here: this submit is
   * about to write its own answer.
   */
  private async verifyAnchors(
    docId: string,
    pending: readonly Annotation[],
    blocked: BlockedAnnotation[],
  ): Promise<Annotation[]> {
    const source = this.options.docs.annotationSource(docId)
    const read = pending.map((annotation) => ({ annotation, locate: relocate(source, annotation.anchor) }))
    await this.store.update(docId, (list) => {
      for (const { annotation, locate } of read) {
        const stored = find(list, annotation.id)
        if (!stored) continue
        if ('failed' in locate) {
          stored.orphan = locate.failed
          stored.blocked = `orphan-${locate.failed}`
          continue
        }
        delete stored.orphan
        delete stored.blocked
      }
    })
    for (const { annotation, locate } of read) {
      if (!('failed' in locate)) continue
      blocked.push({
        annotationId: annotation.id,
        reason: `orphan-${locate.failed}`,
        message:
          locate.failed === 'missing'
            ? 'the text this annotation was made on is no longer in the document'
            : 'the text this annotation was made on now stands in several places',
      })
    }
    return read.filter(({ locate }) => !('failed' in locate)).map(({ annotation }) => annotation)
  }

  /**
   * The issue path (spec-00007-FR-7, design-00001 §12.4), whose whole point is
   * the order: **judge, then move the document, then take the slot**. Steps 1 to
   * 3 leave no trace, so a refusal in them means no transition at all
   * (spec-00007-AC-7.4, AC-10.1, AC-10.3); the transition's write is the line
   * after which nothing is rolled back.
   *
   * `plan.cowrite` is built in step 5 and not before — after the transition — so
   * the baseline the front matter guard and the editor guard hold the session to
   * is `draft` and not the `active` it started from (design-00001 §12.4).
   */
  private async startIssues(
    docId: string,
    target: AnnotationTarget,
    issues: readonly Annotation[],
    agent: AgentConfig,
    blocked: BlockedAnnotation[],
  ): Promise<StartedIssues> {
    const ids = issues.map((annotation) => annotation.id)
    const refuse = async (reason: BlockedReason, message: string): Promise<StartedIssues> => {
      const entries = ids.map((annotationId) => ({ annotationId, reason, message }))
      blocked.push(...entries)
      try {
        await this.blockEach(docId, entries)
      } catch (cause) {
        // The answer stands whether or not the reason could be written down: past
        // the transition nothing may turn into a refusal (see `launchIssues`), and
        // the payload is where the outcome has to be readable either way. Said
        // rather than swallowed — the entry carries why the record failed too.
        const why = (cause as Error).message
        for (const entry of entries) entry.message = `${message} — the reason could not be recorded: ${why}`
      }
      return {}
    }
    if (!target.issueEligible) {
      return refuse('gate-ineligible', `${docId} is in a status no cowrite may be started on (rule-00001-BR-29)`)
    }
    try {
      this.options.sessions.admit('cowrite', docId)
    } catch (cause) {
      if (!(cause instanceof SessionBusyError)) throw cause
      return refuse(cause.reason, cause.message)
    }
    // The same reading the pty seam takes when it starts a session, and not a
    // second one of its own (design-00001 §12.4 第 3 步): a precheck that
    // answered differently would let this submit write the transition and then
    // be refused by the spawn, which is the landing spec-00007-AC-7.4 forbids.
    const unstartable = unrunnable(agent.command)
    if (unstartable !== undefined) return refuse('start-failed', unstartable)
    return this.launchIssues(docId, target, issues, agent, refuse)
  }

  /**
   * Steps 4 to 7 of the issue path: the transition's write, the slot, the batch
   * row, the process. From the write on, nothing is undone — the document stays
   * `draft` and the issues stay where they are, to be submitted again down the
   * no-transition branch (spec-00007-FR-7's compound corner).
   *
   * **Nothing from here on answers 4xx.** The status code draws the line
   * design-00001 §12.3 fixes — 4xx means the batch did not happen at all — and
   * once the transition has written, something *has* happened: every failure past
   * this point is therefore a per-path outcome in a 200 payload, the transition
   * reported as it really went, and the issues held back with a reason. Left to
   * escape, the same failure would answer 4xx over a document that is already
   * `draft` on disk.
   */
  private async launchIssues(
    docId: string,
    target: AnnotationTarget,
    issues: readonly Annotation[],
    agent: AgentConfig,
    refuse: (reason: BlockedReason, message: string) => Promise<StartedIssues>,
  ): Promise<StartedIssues> {
    let transition: SubmitTransition | undefined
    if (target.revision !== undefined) {
      try {
        const moved = await this.options.docs.changeStatus(docId, target.revision)
        // A commit that failed is not a transition that failed: the file is
        // `draft` on disk, so it can be cowritten (spec-00007-AC-7.5).
        transition = { to: target.revision, committed: moved.committed, ...(moved.error ? { error: moved.error } : {}) }
      } catch (cause) {
        // Both refusals land **before** the write, so they are the judgment
        // stage's over again (design-00001 §12.4 (a)): the status lock says
        // `doc-busy`, and a transition the table no longer allows — the status
        // having moved since this submit read it — is the eligibility the batch
        // was held to.
        if (cause instanceof SessionBusyError) return refuse(cause.reason, cause.message)
        if (cause instanceof WorkflowError) return refuse('gate-ineligible', cause.message)
        throw cause
      }
    }
    return { ...(await this.openSession(docId, target, issues, agent, refuse)), transition }
  }

  /**
   * The slot, the batch row and the process (design-00001 §12.4 步骤 5–7). Every
   * way this can fail ends as a held-back batch rather than as a refusal: the
   * second reading of the concurrency rules, a plan that cannot be built on the
   * document as it now stands, a record that cannot be written, and a spawn that
   * threw where it stood.
   */
  private async openSession(
    docId: string,
    target: AnnotationTarget,
    issues: readonly Annotation[],
    agent: AgentConfig,
    refuse: (reason: BlockedReason, message: string) => Promise<StartedIssues>,
  ): Promise<StartedIssues> {
    const ids = issues.map((annotation) => annotation.id)
    let info: SessionInfo
    try {
      info = this.options.sessions.startDeferred(
        this.options.docs.annotationCowritePlan(
          docId,
          issueMaterialLines(issues, target.path),
          this.options.sessions.reservedNumbers(REFERENCE_TYPE),
        ),
        agent.name,
      )
    } catch (cause) {
      // The second reading of the two rules, the one that keeps the books
      // (design-00001 §12.4 (a)): the transition's write and commit sit between
      // it and the first, and another start may have taken the last slot in that
      // window. Anything else that stops the plan being built on the document as
      // it now stands is the same outcome for this batch — it did not start.
      return refuse(...held(cause))
    }
    try {
      const batch = await this.store.addBatch(docId, info.id, ids)
      // The row is on disk before there is a process, for the reason every record
      // of this shape is: a crash leaves something for the startup pass to write
      // off (design-00001 §12.6).
      const started = this.options.sessions.launchTerminal(info.id)
      if (started.status === 'failed') {
        // The seam threw where it stood, so the session is already `failed` and
        // its end callback is handing the batch back (design-00001 §12.6): waited
        // out here, so the answer and the disk say the same thing.
        await this.options.sessions.whenFinished(info.id)
        return refuse('start-failed', started.error ?? `the ${agent.name} agent did not start`)
      }
      return { issues: { batchId: batch.id, sessionId: info.id, annotationIds: ids } }
    } catch (cause) {
      // The row never landed, so there is nothing to hand back and nothing left
      // running on it: the slot goes back (design-00001 §12.6 末段).
      this.options.sessions.abandon(info.id, (cause as Error).message)
      return refuse(...held(cause))
    }
  }

  /**
   * The question path (spec-00007-FR-6): one first call and one thread each, in
   * order, each through the same receipt chain a typed question runs. A refusal
   * is that **one** question's — the thread-serial rule and the session cap are
   * the ones it can meet — and the rest carry on (spec-00007-AC-6.3). With no
   * agent declaring a headless form, every question is held back and the issues
   * are untouched (spec-00007-AC-10.5).
   */
  private async submitQuestions(
    docId: string,
    questions: readonly Annotation[],
    agent: AgentConfig | undefined,
    result: { blocked: BlockedAnnotation[]; warnings: string[] },
  ): Promise<Array<{ annotationId: string; threadId: string; sessionId: string }>> {
    const submitted: Array<{ annotationId: string; threadId: string; sessionId: string }> = []
    for (const annotation of questions) {
      const held = await this.openThread(docId, annotation, agent, result.warnings)
      if ('reason' in held) {
        result.blocked.push(held)
        await this.blockEach(docId, [held])
        continue
      }
      submitted.push({ annotationId: annotation.id, ...held })
    }
    return submitted
  }

  /**
   * One question's thread, or why it was held back.
   *
   * The annotation is marked as gone **before** the call is made, and the thread
   * reference written after it: at most one thread per question, whatever fails in
   * between (spec-00007-FR-6 — one question is one thread). The other order — open
   * first, record second — reads as «held back» when only the *record* failed, and
   * the next submit, finding the annotation still unsubmitted, opens a second
   * thread on the same question. A call that never happened is put back to
   * unsubmitted; a call that did happen but could not be written down is reported
   * as submitted with a warning, because it **is** submitted and the thread is
   * there to be found in the question list.
   */
  private async openThread(
    docId: string,
    annotation: Annotation,
    agent: AgentConfig | undefined,
    warnings: string[],
  ): Promise<{ threadId: string; sessionId: string } | BlockedAnnotation> {
    if (!agent) {
      return {
        annotationId: annotation.id,
        reason: 'no-headless-agent',
        message: 'no agent in the flow config declares a headless form, so nothing can answer a question',
      }
    }
    await this.claim(docId, annotation.id)
    let opened: { sessionId: string; threadId: string }
    try {
      opened = await this.options.openAsk({
        docId,
        question: annotation.text,
        agent: agent.name,
        selection: annotation.anchor,
      })
    } catch (cause) {
      await this.release(docId, annotation.id)
      return {
        annotationId: annotation.id,
        reason: cause instanceof SessionBusyError ? cause.reason : 'start-failed',
        message: (cause as Error).message,
      }
    }
    try {
      await this.store.update(docId, (list) => {
        const stored = find(list, annotation.id)
        if (stored) stored.threadId = opened.threadId
      })
    } catch (cause) {
      const why = (cause as Error).message
      warnings.push(
        `${annotation.id} was asked as ${opened.threadId}, but the reference could not be written down: ${why}`,
      )
    }
    return opened
  }

  /** The annotation taken out of the unsubmitted region before its call is made. */
  private async claim(docId: string, annotationId: string): Promise<void> {
    await this.store.update(docId, (list) => {
      const stored = find(list, annotationId)
      if (!stored) return
      stored.state = 'submitted'
      delete stored.blocked
    })
  }

  /** And put back, when the call it was claimed for never happened. */
  private async release(docId: string, annotationId: string): Promise<void> {
    await this.store.update(docId, (list) => {
      const stored = find(list, annotationId)
      if (stored) stored.state = 'pending'
    })
  }

  /** Record on each annotation why this submit held it back (design-00001 §12.3). */
  private async blockEach(docId: string, entries: readonly BlockedAnnotation[]): Promise<void> {
    await this.store.update(docId, (list) => {
      for (const entry of entries) {
        const annotation = find(list, entry.annotationId)
        // The state stays `pending`: a held-back annotation may be changed,
        // dropped, or submitted again (spec-00007-AC-10.2).
        if (annotation) annotation.blocked = entry.reason
      }
    })
  }

  /**
   * The gates in front of adding or changing an annotation (spec-00007-FR-4,
   * FR-10): a text may not be empty, and a type has to be one this document
   * offers. The issue reading is the same one the submit preview serves, so the
   * entry and the submit cannot disagree about the same document
   * (design-00001 §12.3).
   *
   * The **document** is resolved only when something being set needs it — a type,
   * whose eligibility is a reading of the status, or an anchor, which is a
   * selection of a body. A change of the text alone needs neither, and asking for
   * the document anyway would refuse it on a document that has since been deleted
   * or renamed: the annotations outlive the document (spec-00007-FR-11), and the
   * change entry is as tolerant of that as the delete and the list entries are
   * (spec-00007-FR-3's «every one of them may be changed» has to stay reachable).
   */
  private gate(docId: string, change: AnnotationChange): void {
    if (change.text !== undefined && change.text.trim() === '') {
      throw new AnnotationError('an annotation needs text of its own', 'empty-text')
    }
    if (change.type === undefined && change.anchor === undefined) return
    const target = this.options.docs.annotationTarget(docId)
    if (!target.sound) {
      throw new AnnotationError(`${docId} has front matter problems, so it takes no annotation`, 'doc-anomalous')
    }
    if (change.type === 'issue' && !target.issueEligible) {
      throw new AnnotationError(
        `${docId} is in a status no cowrite may be started on, so it takes no issue (rule-00001-BR-29)`,
        'type-ineligible',
      )
    }
    if (change.type === 'question' && this.headlessAgent() === undefined) {
      throw new AnnotationError(
        'no agent in the flow config declares a headless form, so a question could not be answered',
        'type-ineligible',
      )
    }
  }

  /** The statement that rides with the list (design-00001 §12.3). */
  private preview(list: AnnotationList, target: AnnotationTarget | undefined): SubmitPreview {
    const pending = list.annotations.filter((annotation) => annotation.state === 'pending')
    const issues = pending.filter((annotation) => annotation.type === 'issue').length
    // A document the board cannot resolve, or one whose front matter will not
    // read, offers neither type (spec-00007-FR-4's anomalous document).
    const sound = target?.sound === true ? target : undefined
    return {
      questions: pending.filter((annotation) => annotation.type === 'question').length,
      issues,
      // What **this** submit would do: with no issue to submit there is no
      // cowrite and so no revision round either.
      willTransitionTo: issues > 0 && sound !== undefined ? (sound.revision ?? null) : null,
      issueEligible: sound?.issueEligible === true,
      questionEligible: sound !== undefined && this.headlessAgent() !== undefined,
    }
  }

  /**
   * The agent each path runs (spec-00007-FR-5): two fields, never one — the
   * question path chooses among the agents that declare a headless form and the
   * cowrite path among all of them, and each defaults to the first of **its own**
   * subset (spec-00007-AC-5.6).
   *
   * A name that is no agent, and a question agent that declares no headless form,
   * are refusals of the whole submit: the request itself is wrong. Having no
   * headless agent at all is not — that is the environment's state rather than
   * the request's, and it holds the questions back one by one instead
   * (spec-00007-AC-10.5).
   */
  private chooseAgents(named: { question?: string; cowrite?: string }): ChosenAgents {
    return {
      cowrite: named.cowrite === undefined ? this.options.agents[0]! : this.namedAgent(named.cowrite),
      question:
        named.question === undefined ? this.headlessAgent() : this.headlessNamedAgent(this.namedAgent(named.question)),
    }
  }

  private namedAgent(name: string): AgentConfig {
    const agent = this.options.agents.find((candidate) => candidate.name === name)
    if (!agent) {
      throw new AnnotationError(`${JSON.stringify(name)} is not an agent in the flow config`, 'unknown-agent')
    }
    return agent
  }

  private headlessNamedAgent(agent: AgentConfig): AgentConfig {
    if (agent.headless === undefined) {
      throw new AnnotationError(
        `${JSON.stringify(agent.name)} declares no headless form, so it cannot answer a question`,
        'agent-not-headless',
      )
    }
    return agent
  }

  private headlessAgent(): AgentConfig | undefined {
    return this.options.agents.find((agent) => agent.headless !== undefined)
  }

  /** The target read off a freshly parsed tree (design-00001 §12.3's «not the graph cache»). */
  private freshTarget(docId: string): AnnotationTarget {
    this.options.docs.invalidate()
    return this.options.docs.annotationTarget(docId)
  }

  /** The target, or nothing at all when the board can no longer resolve that id (spec-00007-FR-11). */
  private targetOrNone(docId: string): AnnotationTarget | undefined {
    try {
      return this.options.docs.annotationTarget(docId)
    } catch {
      return undefined
    }
  }

  /** The document's text, or nothing when it is not on disk to be read. */
  private sourceOrNone(docId: string): string | undefined {
    try {
      return this.options.docs.annotationSource(docId)
    } catch {
      return undefined
    }
  }
}

/** The agent each path of one submit runs. */
interface ChosenAgents {
  cowrite: AgentConfig
  question?: AgentConfig
}

/** One batch's end, waiting to be written down (design-00001 §12.6). */
interface Landing {
  sessionId: string
  status: Exclude<BatchStatus, 'cowriting'>
  commit: string | null
}

/** What the issue path produced, if it produced anything. */
interface StartedIssues {
  issues?: { batchId: string; sessionId: string; annotationIds: string[] }
  transition?: SubmitTransition
}

/**
 * The registry's end states mapped to a batch's (design-00001 §12.6), the whole
 * table and no other reading: an exit is `done` **whatever its code**, a stop is
 * `terminated`, a start that never came off is `failed`, and `running` is not an
 * end at all.
 */
const BATCH_END = {
  exited: 'done',
  terminated: 'terminated',
  failed: 'failed',
  running: undefined,
} as const

function find(list: AnnotationList, annotationId: string): Annotation | undefined {
  return list.annotations.find((candidate) => candidate.id === annotationId)
}

/**
 * A batch that did not start, in the word the payload carries: the two
 * concurrency rules keep their own reasons, and everything else that stopped this
 * batch from starting is a start that failed. Never a refusal — past the
 * transition, nothing may answer 4xx (see `launchIssues`).
 */
function held(cause: unknown): [BlockedReason, string] {
  if (cause instanceof SessionBusyError) return [cause.reason, cause.message]
  return ['start-failed', (cause as Error).message]
}

/**
 * What a new annotation has to carry (design-00001 §12.3): a type of the two, a
 * text with something in it, and an anchor of three strings whose selection is
 * not empty. The quote is not among them — the server derives it, so no client
 * can hand in one that says something else (design-00001 §12.1).
 */
function annotationInput(body: unknown): AnnotationInput {
  const { type, text, anchor } = (body ?? {}) as { type?: unknown; text?: unknown; anchor?: unknown }
  if (typeof text !== 'string') {
    throw new WorkflowError('an annotation needs the text the owner wrote')
  }
  return { type: annotationType(type), text, anchor: selectionAnchor(anchor) }
}

/** What a change may carry; every field is optional, and each is checked as it is given. */
function annotationChange(body: unknown): AnnotationChange {
  const { type, text, anchor } = (body ?? {}) as { type?: unknown; text?: unknown; anchor?: unknown }
  if (text !== undefined && typeof text !== 'string') {
    throw new WorkflowError('text is the annotation’s own text, as one string')
  }
  return {
    ...(text === undefined ? {} : { text }),
    ...(type === undefined ? {} : { type: annotationType(type) }),
    ...(anchor === undefined ? {} : { anchor: selectionAnchor(anchor) }),
  }
}

function annotationType(value: unknown): AnnotationType {
  if (value !== 'question' && value !== 'issue') {
    throw new WorkflowError('an annotation is a question or an issue')
  }
  return value
}

function selectionAnchor(value: unknown): SelectionAnchor {
  const { selected, before, after } = (value ?? {}) as Record<string, unknown>
  if (typeof selected !== 'string' || typeof before !== 'string' || typeof after !== 'string') {
    throw new WorkflowError('anchor is the selected text and the context on either side of it, all three strings')
  }
  if (selected === '') {
    throw new WorkflowError('an annotation anchors a selection, and an empty one selects nothing')
  }
  return { selected, before, after }
}

/**
 * What a submit request carries (design-00001 §12.3): whether the editor buffer
 * holds unsaved changes, and the agent each path is to run.
 *
 * The unsaved buffer is the **front end's** declaration and the server verifies
 * nothing: it lives in a browser and there is no second place to observe it. Nor
 * is that a hole — the whole consequence of a false declaration is that the
 * anchors are read against the disk, which is where spec-00007-FR-5 reads them
 * anyway, so the owner sees their own annotations held back as orphans and
 * nothing is written or moved out of turn.
 */
function submitRequest(body: unknown): { unsavedChanges: boolean; agents: { question?: string; cowrite?: string } } {
  const { unsavedChanges, agents } = (body ?? {}) as { unsavedChanges?: unknown; agents?: unknown }
  if (unsavedChanges !== undefined && typeof unsavedChanges !== 'boolean') {
    throw new WorkflowError('unsavedChanges says whether the editor buffer is saved, so it is true or false')
  }
  const { question, cowrite } = (agents ?? {}) as Record<string, unknown>
  for (const [path, name] of Object.entries({ question, cowrite })) {
    if (name !== undefined && typeof name !== 'string') {
      throw new WorkflowError(`agents.${path} must name one of the agents in the flow config`)
    }
  }
  return {
    unsavedChanges: unsavedChanges === true,
    agents: { question: question as string | undefined, cowrite: cowrite as string | undefined },
  }
}

import { mkdirSync, readFileSync, readdirSync, renameSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import type { AnchorFailure, SelectionAnchor } from './annotationAnchor.ts'
import { SerialQueue } from './serialQueue.ts'
import { WorkflowError } from './workflow.ts'

/**
 * Where a document's annotations live, relative to the repo root
 * (design-00001 §12.1): beside `.whiteboard/asks/` and built the same way — one
 * JSON file per document, keyed by document id, every read-modify-write of it in
 * that document's own serial queue, written through a temporary file and a
 * rename. The repo's own `.gitignore` excludes `.whiteboard/`, so nothing of an
 * annotation is tracked and no read or write of this store makes a commit
 * (spec-00007-AC-3.2, FR-11).
 *
 * The likeness to the ask list is not taste: the two are read together on one
 * refresh and written together in one submit, and two storage mechanisms would
 * grow two readings of reconciliation and of concurrency.
 */
export const ANNOTATIONS_DIR = '.whiteboard/annotations'

/**
 * A list is addressed by its document id, and that id is the file's whole name —
 * the same guard the ask list and the session history are addressed under
 * (design-00001 §12.3): no separator reaches this far, so no request can read a
 * file outside this directory.
 */
const ANNOTATION_ID = /^[0-9A-Za-z._-]+$/

/** The two annotation types (spec-00007-FR-1): one wants an answer, the other a change. */
export type AnnotationType = 'question' | 'issue'

/**
 * Whether an annotation has been handed to a path yet (spec-00007-FR-9).
 * **Not** three values: an orphan is a mark on a `pending` annotation rather
 * than a state of its own, which is what keeps the list from growing a third
 * branch (design-00001 §12.1).
 */
export type AnnotationState = 'pending' | 'submitted'

/** Why one annotation, or one whole path, did not go (design-00001 §12.3). */
export type BlockedReason =
  | 'orphan-missing'
  | 'orphan-ambiguous'
  | 'gate-ineligible'
  | 'doc-busy'
  | 'cap-reached'
  | 'start-failed'
  | 'no-headless-agent'

/** How a submitted batch of issues stands (design-00001 §12.6). */
export type BatchStatus = 'cowriting' | 'done' | 'terminated' | 'failed'

/** One annotation on one passage of one document (spec-00007-FR-1, FR-3). */
export interface Annotation {
  id: string
  type: AnnotationType
  text: string
  anchor: SelectionAnchor
  /**
   * The selected text as it read when the annotation was made — the fallback the
   * owner still has when the anchor no longer lands (spec-00007-FR-2). Kept
   * apart from `anchor.selected` although the two start out equal: «the quote
   * outlives the anchor» then holds in the data itself rather than in every
   * reader remembering not to derive it. Always derived by the server from the
   * anchor it was given, on both the create and the re-anchor path
   * (design-00001 §12.1), so no client can hand in a quote that says something
   * else.
   */
  quote: string
  createdAt: string
  state: AnnotationState
  /** Set when a submit's reading of the anchor failed; cleared by a re-anchor or the next submit that lands. */
  orphan?: AnchorFailure
  /** Why the last submit held this one back; cleared by the next submit (design-00001 §12.1). */
  blocked?: BlockedReason
  /** The ask thread a submitted question opened (spec-00007-FR-6); the state itself is the thread's. */
  threadId?: string
  /** The batch a submitted issue belongs to (spec-00007-FR-9); the state itself is the batch's. */
  batchId?: string
}

/**
 * One cowrite session's worth of submitted issues (design-00001 §12.1 and
 * §12.6). The batch is the **single** place that progress is kept: an issue
 * annotation holds no copy of it, or a wrap-up would be N writes and a crash on
 * the third would leave half a batch.
 */
export interface AnnotationBatch {
  id: string
  status: BatchStatus
  /** The registry session, which is what the end callback looks the batch up by. */
  sessionId: string
  annotationIds: string[]
  startedAt: string
  endedAt?: string
  /** The collapse commit's hash, or `null` for «no landed change» (spec-00007-AC-9.4, AC-9.5). */
  commit?: string | null
}

/** One document's whole annotation list, as it sits on disk (spec-00007-FR-3). */
export interface AnnotationList {
  docId: string
  annotations: Annotation[]
  batches: AnnotationBatch[]
  /**
   * How many ids of each kind this list has ever handed out — design-00001
   * §12.1's 取号顺序号 kept as a counter rather than derived from what is still
   * there. An annotation may be deleted (spec-00007-FR-3), so counting the
   * survivors, or even reading the highest one, would hand a **new** annotation
   * the id of one that has gone: a submit's answer names ids, and the answer the
   * owner is still looking at would silently come to mean another passage. Batch
   * numbers come off the same counter, so there is one numbering policy and not
   * two.
   *
   * A file written before this field existed has it derived from every id it can
   * still see, batch membership included.
   */
  issued: IssuedIds
}

interface IssuedIds {
  annotations: number
  batches: number
}

/** What a new annotation is made of; the quote is derived, never given (design-00001 §12.1). */
export interface AnnotationInput {
  type: AnnotationType
  text: string
  anchor: SelectionAnchor
}

/** What a change to an annotation may carry (spec-00007-FR-3): text, type, a new selection. */
export interface AnnotationChange {
  text?: string
  type?: AnnotationType
  anchor?: SelectionAnchor
}

/**
 * Addressed to an annotation that is not in that document's list: 404, the way
 * every other missing resource answers (design-00001 §12.3).
 */
export class NoAnnotationError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'NoAnnotationError'
  }
}

/**
 * The request collides with the state the annotations are in (design-00001
 * §12.3): a second submit while one is in flight, or a change to an annotation
 * that has already gone. 409 rather than 422 by the vocabulary §7 fixes — the
 * request is well formed, it is the current state it runs into.
 */
export class AnnotationConflictError extends Error {
  readonly reason: 'submit-in-flight' | 'already-submitted'

  constructor(message: string, reason: 'submit-in-flight' | 'already-submitted') {
    super(message)
    this.name = 'AnnotationConflictError'
    this.reason = reason
  }
}

/**
 * The annotations on disk (design-00001 §12.1): one JSON file per document,
 * every read-modify-write of it in that document's own turn of a serial queue.
 * The queue is what keeps a submit's batch write, a wrap-up's backfill and a
 * plain edit of an annotation from writing over one another; it is the ask
 * store's and the commit queue's sibling in shape and in nothing else.
 */
export class AnnotationStore {
  private readonly repoRoot: string
  /** One queue per document id; the ask store and the commit queue are separate instances of the same shape. */
  private readonly queue = new SerialQueue()

  constructor(repoRoot: string) {
    this.repoRoot = repoRoot
  }

  /**
   * A document's annotations as they stand, or an empty list (spec-00007-FR-3).
   * The **reading** path, forgiving on purpose: a file that will not parse costs
   * the user that list and not the board. Nothing is ever written back on this
   * reading — see `startWrite`.
   */
  read(docId: string): AnnotationList {
    try {
      return this.load(docId) ?? empty(docId)
    } catch {
      return empty(docId)
    }
  }

  /**
   * One new annotation (spec-00007-FR-1). The quote is derived from the anchor
   * here and on the re-anchor path below, so the two are symmetrical by
   * construction and a client has no way to give them different values
   * (design-00001 §12.1).
   */
  add(docId: string, input: AnnotationInput): Promise<Annotation> {
    return this.write(docId, (list) => {
      list.issued.annotations += 1
      const annotation: Annotation = {
        id: `n-${list.issued.annotations}`,
        type: input.type,
        text: input.text,
        anchor: input.anchor,
        quote: input.anchor.selected,
        createdAt: new Date().toISOString(),
        state: 'pending',
      }
      list.annotations.push(annotation)
      return annotation
    })
  }

  /**
   * Change an annotation that has not gone yet (spec-00007-FR-3): its text, its
   * type, or its selection. A new anchor replaces the quote with it and clears
   * the failure mark — which is the whole of the way out of an orphaned
   * annotation (spec-00007-AC-3.4).
   */
  patch(docId: string, annotationId: string, change: AnnotationChange): Promise<Annotation> {
    return this.write(docId, (list) => {
      const annotation = this.pending(list, annotationId, docId)
      if (change.text !== undefined) annotation.text = change.text
      if (change.type !== undefined) annotation.type = change.type
      if (change.anchor !== undefined) {
        annotation.anchor = change.anchor
        annotation.quote = change.anchor.selected
        delete annotation.orphan
      }
      // Why the **last** submit held it back goes with the change that answers
      // it (design-00002 §16.5: the reason is cleared when the annotation is
      // changed or submitted again). Left standing on a re-anchored annotation it
      // would contradict the very mark the re-anchor has just lifted
      // (spec-00007-AC-3.4).
      if (change.text !== undefined || change.type !== undefined || change.anchor !== undefined) {
        delete annotation.blocked
      }
      return annotation
    })
  }

  /** Drop an annotation that has not gone yet (spec-00007-FR-3). */
  remove(docId: string, annotationId: string): Promise<void> {
    return this.write(docId, (list) => {
      const annotation = this.pending(list, annotationId, docId)
      list.annotations = list.annotations.filter((candidate) => candidate !== annotation)
    })
  }

  /**
   * One turn of that document's queue for a caller that has several things to
   * record at once — a submit's anchor verdicts, a question's thread reference.
   * Whatever the change returns is handed back; a change that throws writes
   * nothing.
   */
  update<T>(docId: string, change: (list: AnnotationList) => T): Promise<T> {
    return this.write(docId, change)
  }

  /**
   * Open a batch over the annotations a submit is handing to one cowrite session
   * (design-00001 §12.6): the row with its number, and the annotations it claims
   * marked as gone. One turn, and before there is a process — a crash then leaves
   * the startup pass something to write off.
   *
   * The number comes off the same counter an annotation's does, so a batch id is
   * never reused either.
   */
  addBatch(docId: string, sessionId: string, annotationIds: readonly string[]): Promise<AnnotationBatch> {
    return this.write(docId, (list) => {
      list.issued.batches += 1
      const batch: AnnotationBatch = {
        id: `b-${list.issued.batches}`,
        status: 'cowriting',
        sessionId,
        annotationIds: [...annotationIds],
        startedAt: new Date().toISOString(),
      }
      list.batches.push(batch)
      for (const annotationId of annotationIds) {
        const annotation = byId(list, annotationId)
        if (!annotation) continue
        annotation.state = 'submitted'
        annotation.batchId = batch.id
        delete annotation.blocked
      }
      return batch
    })
  }

  /**
   * Land a finished cowrite session on its batch (design-00001 §12.6): the end
   * state mapped from the registry's, the time, and the collapse commit. A batch
   * that ended anywhere but `done` gives its annotations back to the unsubmitted
   * region — the batch row itself is kept as history, since nothing here is ever
   * deleted (spec-00007-AC-10.7, AC-10.2).
   *
   * A session no batch on that file names is left alone: most sessions are
   * nobody's batch.
   *
   * A file that cannot be read **refuses**, and the refusal is the caller's to
   * carry (design-00001 §12.6): swallowed, it would leave the batch reading
   * `cowriting` and its annotations submitted for good, with nothing said and no
   * way out until a restart. The caller reports it and keeps the landing to retry
   * (see `Annotations.landBatch`).
   */
  landBatch(
    docId: string,
    sessionId: string,
    status: Exclude<BatchStatus, 'cowriting'>,
    commit: string | null,
  ): Promise<void> {
    return this.write(docId, (list) => {
      const batch = list.batches.find(
        (candidate) => candidate.sessionId === sessionId && candidate.status === 'cowriting',
      )
      if (batch) close(list, batch, status, commit)
    })
  }

  /**
   * Startup reconciliation (spec-00007-AC-10.8): the registry comes up empty
   * (spec-00003-FR-9), so a batch still reading `cowriting` on disk is one the
   * last process took down with it. It is written off as `failed` and its
   * annotations go back to the unsubmitted region — nothing may say «being
   * cowritten» when nothing is. The same hook the ask list's reconciliation
   * hangs on, one directory each.
   */
  reconcile(): void {
    let entries: string[]
    try {
      entries = readdirSync(join(this.repoRoot, ANNOTATIONS_DIR))
    } catch {
      return
    }
    for (const entry of entries.filter((name) => name.endsWith('.json'))) {
      let list: AnnotationList
      try {
        list = this.startWrite(entry.slice(0, -'.json'.length))
      } catch {
        // A file this pass cannot read is a file it must not rewrite: skip it
        // and leave whatever is there for a person to look at.
        continue
      }
      const running = list.batches.filter((batch) => batch.status === 'cowriting')
      if (running.length === 0) continue
      for (const batch of running) close(list, batch, 'failed', null)
      this.save(list)
    }
  }

  /** The annotation a change or a delete addresses, refusing the two ways it can fail. */
  private pending(list: AnnotationList, annotationId: string, docId: string): Annotation {
    const annotation = byId(list, annotationId)
    if (!annotation) {
      throw new NoAnnotationError(`${annotationId} is not an annotation of ${docId}`)
    }
    // Changing or dropping one that has gone would leave a batch pointing at an
    // annotation that is not there, with nothing left to give back when the
    // session ends; deleting submitted annotations is a later round's
    // (spec-00007 §6). The reading is the state alone, so an annotation a
    // terminated batch handed back is editable again (spec-00007-AC-10.7).
    if (annotation.state === 'submitted') {
      throw new AnnotationConflictError(
        `${annotationId} of ${docId} has already been submitted, so it takes no change`,
        'already-submitted',
      )
    }
    return annotation
  }

  /** One read-modify-write of one document's list, in that document's own turn. */
  private write<T>(docId: string, change: (list: AnnotationList) => T): Promise<T> {
    return this.queue.run(docId, () => {
      const list = this.startWrite(docId)
      const result = change(list)
      this.save(list)
      return result
    })
  }

  /**
   * The list a write starts from. Everything but «there is no file yet» refuses:
   * reading an unreadable list as empty and writing that back would erase every
   * annotation the document has, and the file is the only copy
   * (spec-00007-FR-3).
   */
  private startWrite(docId: string): AnnotationList {
    try {
      return this.load(docId) ?? empty(docId)
    } catch (cause) {
      const why = (cause as Error).message
      throw new WorkflowError(`the annotations of ${docId} cannot be read, so nothing may be written over — ${why}`)
    }
  }

  /**
   * The file, or nothing when there is genuinely no list yet. Every other
   * failure throws — an unreadable file and an absent one mean opposite things
   * to a writer.
   */
  private load(docId: string): AnnotationList | undefined {
    if (!ANNOTATION_ID.test(docId)) {
      throw new Error(`${JSON.stringify(docId)} is not a document id this store can address`)
    }
    let text: string
    try {
      text = readFileSync(this.pathOf(docId), 'utf8')
    } catch (cause) {
      if ((cause as NodeJS.ErrnoException).code === 'ENOENT') return undefined
      throw cause
    }
    const parsed = JSON.parse(text) as AnnotationList
    if (!Array.isArray(parsed?.annotations) || !Array.isArray(parsed?.batches)) {
      throw new Error(`${this.pathOf(docId)} holds no annotation list`)
    }
    const list = { docId, annotations: parsed.annotations, batches: parsed.batches, issued: parsed.issued }
    return { ...list, issued: issuedOf(list) }
  }

  private pathOf(docId: string): string {
    return join(this.repoRoot, ANNOTATIONS_DIR, `${docId}.json`)
  }

  /** Written through a temporary file and a rename, so no reader ever sees half a list. */
  private save(list: AnnotationList): void {
    mkdirSync(join(this.repoRoot, ANNOTATIONS_DIR), { recursive: true })
    const path = this.pathOf(list.docId)
    writeFileSync(`${path}.tmp`, `${JSON.stringify(list, null, 2)}\n`)
    renameSync(`${path}.tmp`, path)
  }
}

function empty(docId: string): AnnotationList {
  return { docId, annotations: [], batches: [], issued: { annotations: 0, batches: 0 } }
}

function byId(list: AnnotationList, annotationId: string): Annotation | undefined {
  return list.annotations.find((candidate) => candidate.id === annotationId)
}

/**
 * The counter a file carries, or the highest number every id it still shows can
 * account for — annotations, batches, and the annotations the batches **claimed**,
 * which is where a submitted annotation's number survives its own row. Only a
 * file written before the counter existed takes the derivation; from the first
 * write on, the counter is the record, so a number is never handed out twice even
 * when nothing on the file holds it any more.
 */
function issuedOf(list: Omit<AnnotationList, 'issued'> & { issued?: IssuedIds }): IssuedIds {
  const { issued } = list
  if (typeof issued?.annotations === 'number' && typeof issued.batches === 'number') return issued
  return {
    annotations: highest([
      ...list.annotations.map((annotation) => annotation.id),
      ...list.batches.flatMap((batch) => batch.annotationIds),
    ]),
    batches: highest(list.batches.map((batch) => batch.id)),
  }
}

/** The greatest number among ids of the `<prefix>-<n>` form, or nought when there are none. */
function highest(ids: readonly string[]): number {
  return Math.max(0, ...ids.map((id) => Number(id.slice(id.indexOf('-') + 1)) || 0))
}

/**
 * A batch reaching its end state (design-00001 §12.6): the row is closed, and
 * anything but `done` hands its annotations back to the unsubmitted region.
 */
function close(
  list: AnnotationList,
  batch: AnnotationBatch,
  status: Exclude<BatchStatus, 'cowriting'>,
  commit: string | null,
): void {
  batch.status = status
  batch.endedAt = new Date().toISOString()
  batch.commit = commit
  if (status === 'done') return
  for (const annotation of list.annotations) {
    if (annotation.batchId !== batch.id) continue
    annotation.state = 'pending'
    delete annotation.batchId
  }
}

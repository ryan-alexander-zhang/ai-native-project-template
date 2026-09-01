import type { AnchorFailure } from '../../src/annotationAnchor.ts'
import type { AnnotationType, BatchStatus, BlockedReason } from '../../src/annotationStore.ts'
import type { AnnotationListView } from '../../src/annotations.ts'
import type { SourceRange } from './annotationCoords.ts'
import type { AskExchange, AskThread } from './api.ts'

/**
 * How one row reads. The unsubmitted state is one value and the submitted ones
 * are the path's own — a question's mirror its thread, an issue's its batch — so
 * a row never has to be read in two vocabularies at once (design-00002 §16.1).
 */
export type RowState = 'pending' | AskExchange['outcome'] | Exclude<BatchStatus, 'cowriting'> | 'cowriting'

/** What clicking the body of a row does; the six cases of design-00002 §16.4, whole. */
export type RowAction = 'locate' | 'thread' | 'session' | 'none'

/** One row of the annotation list, synthesised from the three payloads that own the parts. */
export interface AnnotationRow {
  id: string
  type: AnnotationType
  text: string
  quote: string
  state: RowState
  /** Where the anchor lands now — the **one** criterion the failure marks are drawn from. */
  range?: SourceRange
  /** An unsubmitted row whose anchor no longer lands, and why (design-00002 §16.4). */
  orphan?: AnchorFailure
  /** A submitted row whose anchor no longer lands: «the source has changed» (spec-00007-FR-12). */
  changed: boolean
  /** Why the last submit held this one back (design-00002 §16.5). */
  blocked?: BlockedReason
  /** The collapse commit of a finished batch: a hash, or `null` for «no landed change». */
  commit?: string | null
  threadId?: string
  /** The session of a batch still being cowritten, which is what the row click shows. */
  sessionId?: string
  /** An unsubmitted row the last batch handed back, and how that batch ended. */
  handedBack?: 'terminated' | 'failed'
  action: RowAction
}

/** What the badge of each state says, and in which of the ask list's own variants. */
export const ROW_BADGE: Record<RowState, { label: string; variant: 'default' | 'secondary' | 'destructive' | 'outline'; spinning?: boolean }> = {
  pending: { label: 'unsubmitted', variant: 'outline' },
  running: { label: 'running', variant: 'default', spinning: true },
  answered: { label: 'answered', variant: 'secondary' },
  cowriting: { label: 'cowriting', variant: 'default', spinning: true },
  done: { label: 'done', variant: 'secondary' },
  failed: { label: 'failed', variant: 'destructive' },
  terminated: { label: 'terminated', variant: 'secondary' },
}

/**
 * One line for each reason a submit holds an annotation back (design-00002
 * §16.5). The server's own `message` is shown when it sends one; this is the
 * fallback, and there is no eighth entry for the front end to invent.
 */
export const BLOCKED_TEXT: Record<BlockedReason, string> = {
  'orphan-missing': 'the text this was written on is no longer in the document',
  'orphan-ambiguous': 'the text this was written on now stands in several places',
  'gate-ineligible': 'this document is in a status no cowrite may be started on',
  'doc-busy': 'this document already has a session running',
  'cap-reached': 'the session limit is reached',
  'start-failed': 'the agent did not start',
  'no-headless-agent': 'no agent declares a headless form',
}

/** The two ways an unsubmitted anchor fails, said in the list (design-00002 §16.4). */
export const ORPHAN_TEXT: Record<AnchorFailure, string> = {
  missing: 'the source text is gone',
  ambiguous: 'the source text now stands in several places',
}

/** The submitted row's own degradation, which is no failure at all (spec-00007-FR-12). */
export const CHANGED_TEXT = 'the source has changed'

/** The batch that ended without finishing, said on the rows it handed back (design-00002 §16.6). */
export const HANDED_BACK_TEXT: Record<'terminated' | 'failed', string> = {
  terminated: 'the last cowrite was stopped',
  failed: 'the last cowrite failed',
}

/** Why the whole submit was refused, by the word the server put on it (design-00002 §16.5). */
export const SUBMIT_REFUSAL: Record<string, string> = {
  'submit-in-flight': 'a submit of this document is already under way',
  'doc-missing': 'this document is no longer on disk under that id; nothing was submitted',
  'doc-anomalous': 'this document’s front matter will not parse, so it takes no annotation',
  'unsaved-buffer': 'save the buffer before submitting the annotations of this document',
  'empty-submit': 'there is no unsubmitted annotation to submit',
  'unknown-agent': 'that is not an agent in the flow config',
  'agent-not-headless': 'that agent declares no headless form, so it cannot answer a question',
}

/**
 * The list as one reading of three payloads, each part taken from the one place
 * that owns it (design-00002 §16.4): the annotations and their freshly computed
 * `locate` from `GET /api/annotations/:id`, a question's state from the **last
 * exchange** of its thread in `GET /api/asks/:id`, and an issue's from its batch
 * row. Nothing is synthesised out of the session listing — the batch's own status
 * is backfilled server-side, and a second lookup would be a second thing to
 * drift.
 *
 * The order is the order they were created in, unsubmitted ones mixed among the
 * rest: an annotation belongs where the reading that produced it belongs, and
 * which of them are still unsubmitted is readable off a badge.
 */
export function annotationRows(view: AnnotationListView, threads: readonly AskThread[]): AnnotationRow[] {
  return view.annotations.map((annotation) => {
    const range = 'failed' in annotation.locate ? undefined : annotation.locate
    const batch = view.batches.find((candidate) => candidate.id === annotation.batchId)
    const pending = annotation.state === 'pending'
    const row: AnnotationRow = {
      id: annotation.id,
      type: annotation.type,
      text: annotation.text,
      quote: annotation.quote,
      state: pending ? 'pending' : submittedState(annotation.type, annotation.threadId, batch?.status, threads),
      ...(range === undefined ? {} : { range }),
      ...(pending && 'failed' in annotation.locate ? { orphan: annotation.locate.failed } : {}),
      changed: !pending && range === undefined,
      ...(annotation.blocked === undefined ? {} : { blocked: annotation.blocked }),
      ...(batch?.status === 'done' ? { commit: batch.commit ?? null } : {}),
      ...(annotation.threadId === undefined ? {} : { threadId: annotation.threadId }),
      ...(batch?.status === 'cowriting' ? { sessionId: batch.sessionId } : {}),
      ...(pending ? handedBack(view, annotation.id) : {}),
      action: 'none',
    }
    return { ...row, action: rowAction(row) }
  })
}

/**
 * A submitted annotation's state, mirrored from whichever path holds it. A
 * question whose thread is not in the payload yet reads `running`: the first call
 * has gone and the list is the slower of the two reads (spec-00007-AC-9.10).
 */
function submittedState(
  type: AnnotationType,
  threadId: string | undefined,
  batch: BatchStatus | undefined,
  threads: readonly AskThread[],
): RowState {
  if (type === 'issue') return batch ?? 'cowriting'
  return threads.find((thread) => thread.id === threadId)?.exchanges.at(-1)?.outcome ?? 'running'
}

/**
 * The batch that gave this annotation back (spec-00007-FR-10). Its `batchId` is
 * cleared when that happens, so membership in the batch row is the only trace
 * left — the newest such row is the one that is said out loud.
 */
function handedBack(view: AnnotationListView, annotationId: string): { handedBack?: 'terminated' | 'failed' } {
  let over: 'terminated' | 'failed' | undefined
  for (const batch of view.batches) {
    if (!batch.annotationIds.includes(annotationId)) continue
    if (batch.status === 'terminated' || batch.status === 'failed') over = batch.status
  }
  return over === undefined ? {} : { handedBack: over }
}

/**
 * The six cases of design-00002 §16.4's dispatch table, written out with no
 * «otherwise» for an implementer to fill in. «None» is really nothing at all:
 * the row the user clicked has nowhere to go, and inventing a flash or a toast
 * for it is noise — the disabled locate button and its tooltip carry that signal.
 */
function rowAction(row: AnnotationRow): RowAction {
  if (row.state === 'pending') return row.range === undefined ? 'none' : 'locate'
  if (row.type === 'question') return 'thread'
  if (row.state === 'cowriting') return 'session'
  return row.range === undefined ? 'none' : 'locate'
}

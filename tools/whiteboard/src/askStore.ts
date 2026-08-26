import { mkdirSync, readFileSync, readdirSync, renameSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { SerialQueue } from './serialQueue.ts'
import { WorkflowError } from './workflow.ts'

/**
 * Where a document's ask list lives, relative to the repo root
 * (design-00001 §10.2): beside `.whiteboard/sessions/`, which the repo's own
 * .gitignore excludes — the questions and their answers are board state, not
 * documents, so nothing of them reaches `docs/` or a commit
 * (spec-00005-AC-5.2).
 */
export const ASKS_DIR = '.whiteboard/asks'

/**
 * A list is addressed by its document id, and that id is the file's whole name.
 * Anything else is not one: no separator reaches this far, so no request can
 * read a file outside the asks directory (design-00001 §7).
 */
const ASK_ID = /^[0-9A-Za-z._-]+$/

/**
 * How one question stands (design-00001 §10.2). `running` is the only state a
 * second submit on that thread is refused for, and the only one a restart has to
 * reconcile away (spec-00005-FR-7, AC-5.3).
 */
export type AskOutcome = 'running' | 'answered' | 'failed' | 'terminated'

/** One question and, once the call is over, its answer (spec-00005-FR-2). */
export interface AskExchange {
  question: string
  askedAt: string
  answer?: string
  answeredAt?: string
  outcome: AskOutcome
  /**
   * Why a question that failed has no answer (design-00001 §10.3). A call can
   * exit zero and still answer nothing — the CLI reporting its own error, or
   * output the capture cannot read — so the process's story («exited 0») and the
   * question's are two stories, and this is the one the list has to tell
   * (spec-00005-FR-7). Absent on every other outcome.
   */
  reason?: string
  /** The registry session of the call, which is what a panel row or a notice is looked up by (design-00001 §10.3). */
  runSessionId: string
}

/** One thread: a question, its follow-ups, and the CLI conversation they share. */
export interface AskThread {
  id: string
  /** The agent the thread was opened with; a follow-up runs no other (spec-00005-FR-2). */
  agent: string
  /** The CLI's own resume id, filled in by the first answered call (design-00001 §10.2). */
  resumeId?: string
  /**
   * Set when a call resuming this thread failed, and cleared by the next one
   * that lands: the continuation is marked rather than quietly swapped for a
   * fresh conversation (design-00001 §10.2 域主裁定, 2026-08-26).
   */
  resumeInvalid?: boolean
  exchanges: AskExchange[]
}

/** One document's whole ask list, as it sits on disk (spec-00005-FR-5). */
export interface AskList {
  docId: string
  threads: AskThread[]
}

/** How a finished call is landed on its thread (design-00001 §10.3 收尾). */
export interface AskResult {
  outcome: Exclude<AskOutcome, 'running'>
  answer?: string
  /** Why it failed, when it failed (see {@link AskExchange.reason}). */
  reason?: string
  resumeId?: string
  /** Whether the call carried a resume id, which is what a failure marks the thread on. */
  resumed: boolean
}

/**
 * A submit refused because that thread already has a call running
 * (spec-00005-FR-7): follow-ups are serial by nature, and a thread is the one
 * thing an ask is exclusive of — never the document (spec-00005-FR-6).
 */
export class AskBusyError extends Error {
  readonly reason = 'thread-busy'

  constructor(message: string) {
    super(message)
    this.name = 'AskBusyError'
  }
}

/**
 * The ask lists on disk (design-00001 §10.2): one JSON file per document, every
 * read-modify-write of it in that document's own serial queue. The queue is what
 * keeps two threads of the same document — which spec-00005-AC-6.3 requires to
 * run at once — from writing over each other's wrap-up; it is the commit queue's
 * sibling in shape and nothing else, since an ask makes no commit at all.
 */
export class AskStore {
  private readonly repoRoot: string
  /** One queue per document id; the commit queue is a separate instance of the same shape. */
  private readonly queue = new SerialQueue()

  constructor(repoRoot: string) {
    this.repoRoot = repoRoot
  }

  /**
   * A document's ask list as it stands, or an empty one (spec-00005-FR-5). This
   * is the **reading** path, and it is forgiving on purpose: a file that will
   * not parse costs the user that list and not the board. Nothing may be
   * written back on this reading — see `startWrite`.
   */
  read(docId: string): AskList {
    try {
      return this.load(docId) ?? { docId, threads: [] }
    } catch {
      return { docId, threads: [] }
    }
  }

  /**
   * The whole receipt chain of one submit, in the order design-00001 §10.2 fixes
   * it: the thread-serial judgment, then `admit` — the caller's own document
   * check, cap accounting and registry slot — and only once both have passed,
   * the `running` exchange on disk. A refusal on either side writes nothing
   * (spec-00005-AC-6.4, AC-7.1); a record written before the process exists is
   * what lets a crash be reconciled (AC-5.3). The whole chain is one turn of
   * this document's queue, so two threads submitting at once are ordered rather
   * than interleaved.
   */
  open<T extends { id: string; agent: string }>(
    docId: string,
    submit: { question: string; threadId?: string; resend?: boolean },
    admit: (thread: AskThread) => T,
  ): Promise<{ thread: AskThread; admitted: T }> {
    return this.queue.run(docId, () => {
      const list = this.startWrite(docId)
      const thread = submit.threadId === undefined ? newThread(list) : requireThread(list, submit.threadId, docId)
      if (thread.exchanges.some((exchange) => exchange.outcome === 'running')) {
        throw new AskBusyError(`ask thread ${thread.id} of ${docId} already has a call running`)
      }
      // Where the question goes is settled before anything is admitted: a
      // resend that has nothing to resend is refused with nothing spawned.
      const last = thread.exchanges.at(-1)
      if (submit.resend && (!last || last.outcome === 'answered')) {
        throw new WorkflowError(`ask thread ${thread.id} of ${docId} has no unanswered question to resend`)
      }
      const admitted = admit(thread)
      thread.agent = admitted.agent
      const asked: AskExchange = {
        question: submit.question,
        askedAt: new Date().toISOString(),
        outcome: 'running',
        runSessionId: admitted.id,
      }
      // Only the *question* is kept for good (spec-00005-FR-3): a **resend**
      // rewrites its own exchange where it stands, so the list grows by a
      // question and never by a retry. A follow-up appends, even after a
      // question that failed — the caller says which of the two this is, because
      // the record alone cannot tell «ask that again» from «ask something else
      // next» and guessing would file the new question over the old one.
      if (submit.resend) thread.exchanges[thread.exchanges.length - 1] = asked
      else thread.exchanges.push(asked)
      this.write(list)
      return { thread, admitted }
    })
  }

  /**
   * Land a finished call on its thread (design-00001 §10.3): the outcome always,
   * the answer and the resume id when there was one to capture. A thread whose
   * file no longer holds it is left alone — the list is addressed by document id
   * and outlives nothing else, so there is nothing to recreate here.
   */
  finish(docId: string, threadId: string, result: AskResult): Promise<void> {
    return this.queue.run(docId, () => {
      let list: AskList
      try {
        list = this.startWrite(docId)
      } catch {
        // A list that cannot be read is a list that must not be written over.
        // This one call's record is lost; every other thread on that file keeps
        // its own, which is the trade the other way round.
        return
      }
      const thread = list.threads.find((candidate) => candidate.id === threadId)
      const exchange = thread?.exchanges.at(-1)
      if (!thread || !exchange) return
      exchange.outcome = result.outcome
      if (result.answer !== undefined) {
        exchange.answer = result.answer
        exchange.answeredAt = new Date().toISOString()
      }
      // The reason belongs to this landing and to no other: a question resent
      // and answered must not still carry why it failed last time.
      if (result.reason !== undefined) exchange.reason = result.reason
      else delete exchange.reason
      // Latest id wins, not first: a CLI is free to hand back a **new** id for
      // each resumed print run, and keeping the first would send every later
      // follow-up back to a conversation that has since moved on. The id the
      // last answer came with is the one that continues it.
      if (result.resumeId !== undefined) thread.resumeId = result.resumeId
      // Honest marking rather than a silent fresh conversation: a resume that
      // **failed** leaves the thread flagged, and the next one that lands clears
      // it (design-00001 §10.2). A call the user stopped says nothing about the
      // continuation — they stopped it, the CLI did not refuse it.
      if (result.outcome === 'answered') delete thread.resumeInvalid
      else if (result.resumed && result.outcome === 'failed') thread.resumeInvalid = true
      this.write(list)
    })
  }

  /**
   * Startup reconciliation (spec-00005-AC-5.3). The registry comes up empty
   * (spec-00003-FR-9), so a `running` exchange on disk is a call the last
   * process took down with it: nothing may say «in progress» when nothing is,
   * and the question is left resendable rather than stuck.
   */
  reconcile(): void {
    let entries: string[]
    try {
      entries = readdirSync(join(this.repoRoot, ASKS_DIR))
    } catch {
      return
    }
    for (const entry of entries.filter((name) => name.endsWith('.json'))) {
      let list: AskList
      try {
        list = this.startWrite(entry.slice(0, -'.json'.length))
      } catch {
        // A file this pass cannot read is a file it must not rewrite: skip it
        // and leave whatever is there for a person to look at.
        continue
      }
      const running = list.threads.flatMap((thread) =>
        thread.exchanges.filter((exchange) => exchange.outcome === 'running'),
      )
      if (running.length === 0) continue
      for (const exchange of running) {
        exchange.outcome = 'failed'
        exchange.reason = 'the service stopped while this call was running'
      }
      this.write(list)
    }
  }

  /**
   * The list a write starts from. Everything but «there is no file yet» refuses:
   * reading an unreadable list as empty and then writing that back would erase
   * every thread the document has — the one failure this store cannot recover
   * from, since the list is the only copy (spec-00005-FR-5).
   */
  private startWrite(docId: string): AskList {
    try {
      return this.load(docId) ?? { docId, threads: [] }
    } catch (cause) {
      throw new WorkflowError(
        `the ask list of ${docId} cannot be read, so nothing may be written over it — ${(cause as Error).message}`,
      )
    }
  }

  /**
   * The file, or nothing when there is genuinely no list yet. Every other
   * failure throws — an unreadable file and an absent one mean opposite things
   * to a writer.
   */
  private load(docId: string): AskList | undefined {
    if (!ASK_ID.test(docId)) throw new Error(`${JSON.stringify(docId)} is not a document id this store can address`)
    let text: string
    try {
      text = readFileSync(this.pathOf(docId), 'utf8')
    } catch (cause) {
      if ((cause as NodeJS.ErrnoException).code === 'ENOENT') return undefined
      throw cause
    }
    const parsed = JSON.parse(text) as AskList
    if (!Array.isArray(parsed?.threads)) throw new Error(`${this.pathOf(docId)} holds no list of threads`)
    return { docId, threads: parsed.threads }
  }

  private pathOf(docId: string): string {
    return join(this.repoRoot, ASKS_DIR, `${docId}.json`)
  }

  /** Written through a temporary file and a rename, so no reader ever sees half a list. */
  private write(list: AskList): void {
    mkdirSync(join(this.repoRoot, ASKS_DIR), { recursive: true })
    const path = this.pathOf(list.docId)
    writeFileSync(`${path}.tmp`, `${JSON.stringify(list, null, 2)}\n`)
    renameSync(`${path}.tmp`, path)
  }
}

/** A new thread takes the next number in the list's own order (design-00001 §10.2). */
function newThread(list: AskList): AskThread {
  const thread: AskThread = { id: `t-${list.threads.length + 1}`, agent: '', exchanges: [] }
  list.threads.push(thread)
  return thread
}

function requireThread(list: AskList, threadId: string, docId: string): AskThread {
  const thread = list.threads.find((candidate) => candidate.id === threadId)
  if (!thread) throw new WorkflowError(`${threadId} is not a thread of the ask list of ${docId}`)
  return thread
}

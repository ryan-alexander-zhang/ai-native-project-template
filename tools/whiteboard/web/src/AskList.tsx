import { LoaderCircle, RotateCcw, Send } from 'lucide-react'
import { useState } from 'react'
import type { AskExchange, AskThread } from './api.ts'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Preview } from './Preview.tsx'
import { stamp } from './status.ts'

export interface AskListProps {
  /** The document's threads, newest last — the ask list as it stands (spec-00005-FR-9). */
  threads: AskThread[]
  /** The thread the board is located on, which is the expanded one (design-00002 §10, §14). */
  located?: string
  onLocate: (threadId?: string) => void
  /** Ask on in this thread; what comes back says whether it went (see {@link AskEntry}). */
  onFollowUp: (threadId: string, question: string) => Promise<boolean>
  /** Put a failed or stopped question again, on the thread it belongs to (spec-00005-FR-7). */
  onResend: (threadId: string, question: string) => void
}

/** What a thread reads as: how its last call ended, since that is the only one still moving. */
function outcomeOf(thread: AskThread): AskExchange['outcome'] | undefined {
  return thread.exchanges.at(-1)?.outcome
}

const OUTCOME_VARIANT: Record<string, 'default' | 'secondary' | 'destructive'> = {
  running: 'default',
  answered: 'secondary',
  failed: 'destructive',
  terminated: 'secondary',
}

/** The row's one line: a thread is known by the question it was opened with. */
function subject(thread: AskThread): string {
  return thread.exchanges[0]?.question.split('\n')[0] ?? ''
}

/**
 * One thread, expanded: every question with its answer, and the way to carry on
 * asking. The answer is Markdown, rendered through the preview's own pipeline —
 * the capture layer has already taken the control sequences off it
 * (spec-00005-FR-3, design-00001 §10.1).
 */
function Thread({
  thread,
  onFollowUp,
  onResend,
}: {
  thread: AskThread
  onFollowUp: (threadId: string, question: string) => Promise<boolean>
  onResend: (threadId: string, question: string) => void
}) {
  const [question, setQuestion] = useState('')
  /** A follow-up already on its way: this thread takes one call at a time (spec-00005-AC-7.1). */
  const [sending, setSending] = useState(false)
  const outcome = outcomeOf(thread)
  // A thread answers one call at a time (spec-00005-AC-7.1), and a continuation
  // the CLI has refused cannot be carried on at all: the way on from there is a
  // new question, and the marked thread says so rather than swapping the
  // conversation for a fresh one behind the user's back (design-00001 §10.2).
  const blocked = outcome === 'running' || thread.resumeInvalid === true
  const empty = question.trim() === ''

  /** Cleared only once the follow-up is away, for the reason `AskEntry.submit` gives. */
  async function send() {
    if (blocked || empty || sending) return
    setSending(true)
    try {
      if (await onFollowUp(thread.id, question)) setQuestion('')
    } finally {
      setSending(false)
    }
  }

  return (
    <div className="flex flex-col gap-3 px-3 pb-3">
      {thread.exchanges.map((exchange, index) => (
        <article key={`${exchange.askedAt}-${index}`} className="flex flex-col gap-1">
          <p className="text-sm font-medium whitespace-pre-wrap">{exchange.question}</p>
          <span className="text-muted-foreground text-[10px]">{stamp(exchange.askedAt)}</span>
          {exchange.answer === undefined ? null : (
            <div className="text-sm">
              <Preview markdown={exchange.answer} />
            </div>
          )}
          {/*
            Why this question has no answer, in the words closest to the cause
            (design-00001 §10.3). A call can exit zero and still answer nothing —
            the CLI reporting its own error — so the process's own story and the
            question's are two stories, and this is the one the reader of the
            list came for (spec-00005-FR-7).
          */}
          {exchange.reason === undefined ? null : (
            <p className="text-destructive text-xs whitespace-pre-wrap">{exchange.reason}</p>
          )}
          {/*
            Resending rewrites the question where it stands, and the server does
            that to the thread's **last** exchange (design-00001 §10.2), so that
            is the only one it is offered on.
          */}
          {index === thread.exchanges.length - 1 && (outcome === 'failed' || outcome === 'terminated') ? (
            <Button
              variant="outline"
              size="sm"
              className="self-start"
              aria-label={`Resend the question of ${thread.id}`}
              onClick={() => onResend(thread.id, exchange.question)}
            >
              <RotateCcw className="size-4" aria-hidden />
              Resend
            </Button>
          ) : null}
        </article>
      ))}

      <div className="flex items-end gap-2">
        <textarea
          aria-label={`Follow-up question on ${thread.id}`}
          value={question}
          onChange={(event) => setQuestion(event.target.value)}
          disabled={blocked}
          placeholder="ask a follow-up in this thread"
          rows={2}
          className="border-input focus-visible:ring-ring/50 min-h-14 flex-1 rounded-md border bg-transparent px-3 py-2 text-sm outline-none focus-visible:ring-[3px] disabled:opacity-50"
        />
        <Button size="sm" onClick={send} disabled={blocked || empty || sending}>
          {sending ? (
            <LoaderCircle className="size-4 animate-spin" aria-hidden />
          ) : (
            <Send className="size-4" aria-hidden />
          )}
          Send
        </Button>
      </div>
      {thread.resumeInvalid === true ? (
        <p className="text-muted-foreground text-xs">
          this conversation can no longer be continued — resend the question, or open a new one
        </p>
      ) : null}
    </div>
  )
}

/**
 * The ask list of spec-00005-FR-9: the editor's third view state, beside editing
 * and preview. Every thread of the document is a row — the question it was
 * opened with and how its last call ended — and opening one shows the whole of
 * its questions and answers with the way to carry on asking.
 */
export function AskList({ threads, located, onLocate, onFollowUp, onResend }: AskListProps) {
  if (threads.length === 0) {
    return <p className="text-muted-foreground p-3 text-xs">no questions on this document yet</p>
  }
  return (
    <ul aria-label="Ask threads" className="divide-y">
      {threads.map((thread) => {
        const outcome = outcomeOf(thread)
        const expanded = located === thread.id
        return (
          <li key={thread.id}>
            <button
              type="button"
              aria-expanded={expanded}
              onClick={() => onLocate(expanded ? undefined : thread.id)}
              className="hover:bg-accent flex w-full items-center gap-2 px-3 py-2 text-left text-xs"
            >
              {outcome === 'running' ? (
                <LoaderCircle className="size-3.5 shrink-0 animate-spin" aria-hidden />
              ) : null}
              <span className="min-w-0 flex-1 truncate">{subject(thread)}</span>
              <Badge variant={OUTCOME_VARIANT[outcome ?? ''] ?? 'secondary'} className="text-[10px]">
                {outcome}
              </Badge>
            </button>
            {expanded ? <Thread thread={thread} onFollowUp={onFollowUp} onResend={onResend} /> : null}
          </li>
        )
      })}
    </ul>
  )
}

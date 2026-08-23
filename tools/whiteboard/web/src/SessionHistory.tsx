import { ArrowLeft, Bot, History } from 'lucide-react'
import { useEffect, useState } from 'react'
import { toast } from 'sonner'
import { type SessionHistoryEntry, type SessionHistoryMeta, api } from './api.ts'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { stamp } from './status.ts'

export interface SessionHistoryProps {
  open: boolean
  onOpenChange: (open: boolean) => void
}

/**
 * Every session that has already ended, and any one of them read whole
 * (spec-00001-FR-54). Both come off disk, so the list survives a restart of the
 * board — which is the point of writing them down at all (spec-00001-AC-54.2).
 *
 * Newest first: the session you want to look at is nearly always the last one.
 */
export function SessionHistory({ open, onOpenChange }: SessionHistoryProps) {
  const [records, setRecords] = useState<SessionHistoryMeta[]>()
  const [reading, setReading] = useState<SessionHistoryEntry>()

  useEffect(() => {
    if (!open) return
    let live = true
    setReading(undefined)
    api
      .sessionHistory()
      .then((history) => {
        if (!live) return
        setRecords(
          [...history].sort((a, b) => (a.startedAt < b.startedAt ? 1 : a.startedAt > b.startedAt ? -1 : 0)),
        )
      })
      .catch((error) => {
        if (live) toast.error(error instanceof Error ? error.message : String(error))
      })
    return () => {
      live = false
    }
  }, [open])

  async function read(id: string) {
    try {
      setReading(await api.sessionTranscript(id))
    } catch (error) {
      toast.error(error instanceof Error ? error.message : String(error))
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-3xl">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <History className="size-4" aria-hidden />
            {reading ? reading.meta.id : 'Session history'}
          </DialogTitle>
          <DialogDescription>
            {reading
              ? `${reading.meta.kind} · ${reading.meta.docId}`
              : 'Sessions that have ended. Pick one to read its transcript.'}
          </DialogDescription>
        </DialogHeader>

        {reading ? (
          <>
            <Button
              variant="ghost"
              size="sm"
              className="self-start"
              onClick={() => setReading(undefined)}
            >
              <ArrowLeft className="size-4" aria-hidden />
              Back to the list
            </Button>
            {/* Read-only, and the whole of it: a transcript is evidence, not a draft. */}
            <pre
              aria-label={`Transcript of ${reading.meta.id}`}
              className="bg-muted max-h-[60vh] overflow-auto rounded-md p-3 font-mono text-xs whitespace-pre-wrap"
            >
              {reading.transcript}
            </pre>
          </>
        ) : records === undefined ? (
          <p className="text-muted-foreground text-xs">reading the history…</p>
        ) : records.length === 0 ? (
          <p className="text-muted-foreground text-xs">no sessions yet</p>
        ) : (
          <ul aria-label="Session history" className="max-h-[60vh] overflow-y-auto">
            {records.map((record) => (
              <li key={record.id}>
                <button
                  type="button"
                  onClick={() => void read(record.id)}
                  className="hover:bg-accent flex w-full flex-wrap items-center gap-2 rounded-md px-2 py-1.5 text-left text-xs"
                >
                  <Badge variant="secondary" className="text-[10px]">
                    {record.kind}
                  </Badge>
                  <span className="truncate font-mono">{record.docId}</span>
                  <span className="text-muted-foreground flex items-center gap-1">
                    <Bot className="size-3" aria-hidden />
                    {record.agent}
                  </span>
                  <span className="text-muted-foreground ml-auto">
                    {stamp(record.startedAt)} → {stamp(record.endedAt)}
                  </span>
                  {/* How it ended, as it was recorded: the status, and the code
                      when the process reported one (spec-00001-AC-54.1). */}
                  <span className={record.status === 'failed' ? 'text-destructive font-mono' : 'font-mono'}>
                    {record.exitCode === undefined ? record.status : `${record.status} ${record.exitCode}`}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </DialogContent>
    </Dialog>
  )
}

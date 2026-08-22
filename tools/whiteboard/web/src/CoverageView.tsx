import { Gauge } from 'lucide-react'
import { useState } from 'react'
import type { DocNode } from '../../src/docRepository.ts'
import type { CoverageRow } from './api.ts'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { COVERAGE } from './coverageMarks.ts'
import { typeIcon } from './status.ts'

export interface CoverageViewProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** The payload, or nothing while the first read is in flight. */
  rows?: CoverageRow[]
  /** The graph the type icon of each row is read off; a row carries no type of its own. */
  nodes: DocNode[]
  /** Go to the document holding this item (spec-00002-FR-12). */
  onPick: (docId: string) => void
}

/**
 * The global coverage view of spec-00002-FR-10 … FR-12: every spec and rule in
 * the repo, its three counts, and — for the one row that is open — each item
 * with its state. The same full-screen dialog the command palette uses, so it
 * takes no side slot and opens whatever else is on show (design-00002 §3).
 *
 * Nothing is derived here. The counts and the states come off the payload the
 * server computed, which is the one `/items` serves (design-00001 §2).
 */
export function CoverageView({ open, onOpenChange, rows, nodes, onPick }: CoverageViewProps) {
  // One row at a time, held by document id (spec-00002-FR-11, design-00002 §10).
  // Resolving it against the payload on every render is what keeps it through a
  // refresh and what drops it when the document it named has left the tree
  // (spec-00002-AC-11.5).
  const [chosen, setChosen] = useState<string>()
  const expanded = rows?.some((row) => row.docId === chosen) ? chosen : undefined
  const toggle = (docId: string) => setChosen((current) => (current === docId ? undefined : docId))

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-3xl">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <Gauge className="size-4" aria-hidden />
            Coverage
          </DialogTitle>
          <DialogDescription>
            Every spec and rule in the repo. Pick an item to go to the document that declares it.
          </DialogDescription>
        </DialogHeader>

        {rows === undefined ? (
          <p className="text-muted-foreground text-xs">reading the coverage…</p>
        ) : rows.length === 0 ? (
          <p className="text-muted-foreground text-xs">no spec or rule under docs/ yet</p>
        ) : (
          <ul aria-label="Coverage by document" className="max-h-[60vh] overflow-y-auto">
            {rows.map((row) => {
              const Icon = typeIcon(nodes.find((node) => node.id === row.docId)?.type)
              return (
                <li key={row.docId}>
                  {/* A real button, so the row is reached by Tab and fired by
                      Enter as well as by the pointer (design-00002 §6). */}
                  <button
                    type="button"
                    aria-expanded={expanded === row.docId}
                    onClick={() => toggle(row.docId)}
                    className="hover:bg-accent flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-xs"
                  >
                    <Icon className="size-4 shrink-0 opacity-70" aria-hidden />
                    <span className="shrink-0 truncate font-mono">{row.docId}</span>
                    <span className="text-muted-foreground truncate">{row.title}</span>
                    <span className="ml-auto flex shrink-0 items-center gap-3">
                      <Count state="verified" count={row.verified} />
                      <Count state="failing" count={row.failing} />
                      <Count state="uncovered" count={row.uncovered} />
                    </span>
                  </button>
                  {expanded === row.docId ? <Expansion row={row} onPick={onPick} /> : null}
                </li>
              )
            })}
          </ul>
        )}
      </DialogContent>
    </Dialog>
  )
}

/**
 * One of the three counts. It reads as a number and a state — the icon and the
 * accessible name both say which one — and the token only colours it
 * (design-00002 §6, spec-00001-AC-32.6).
 */
function Count({ state, count }: { state: keyof typeof COVERAGE; count: number }) {
  const { Icon, label, token } = COVERAGE[state]
  return (
    <span role="img" aria-label={`${count} ${label}`} className="flex items-center gap-1 font-mono">
      <Icon className="size-3.5" aria-hidden style={{ color: token }} />
      {count}
    </span>
  )
}

/**
 * The opened row (spec-00002-FR-11): the document's items, each its id and its
 * state, and each a way into the document that declares it (FR-12). A document
 * with no items says so rather than opening onto nothing.
 */
function Expansion({ row, onPick }: { row: CoverageRow; onPick: (docId: string) => void }) {
  return (
    <div aria-label={`Items of ${row.docId}`} className="mt-1 mb-2 ml-6 space-y-0.5 border-l pl-3">
      {row.items.length === 0 ? (
        <p className="text-muted-foreground text-xs">no requirement items</p>
      ) : (
        row.items.map((item) => {
          const { Icon, label, token } = COVERAGE[item.coverage]
          return (
            <button
              key={item.id}
              type="button"
              onClick={() => onPick(row.docId)}
              className="hover:bg-accent flex w-full items-center gap-2 rounded-md px-2 py-1 text-left font-mono text-xs"
            >
              <Icon role="img" aria-label={label} className="size-3.5 shrink-0" style={{ color: token }} />
              {item.id}
            </button>
          )
        })
      )}
    </div>
  )
}

import { ExternalLink, PanelRight } from 'lucide-react'
import type { ReactNode } from 'react'
import type { AcceptanceRow, Criterion, RequirementItem } from '../../src/requirements.ts'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { InlineMarkdown } from './InlineMarkdown.tsx'
import type { DetailTarget } from './subCanvas.ts'

export interface DetailsProps {
  target: DetailTarget
  /** Leave the sub-canvas for the record this row came from (spec-00001-AC-37.6). */
  onGoToRecord: (recordId: string) => void
}

/**
 * The detail panel of spec-00001-FR-37: the card identifies, the panel reads
 * (design-00002 §9). It takes the right slot the sub-canvas left free, and it
 * offers no way to write anything — the sub-canvas is read-only (FR-35).
 */
export function Details({ target, onGoToRecord }: DetailsProps) {
  return (
    <section aria-label={`Details of ${target.id}`} className="flex h-full min-h-0 flex-col">
      <header className="flex items-center gap-2 border-b px-3 py-2">
        <PanelRight className="size-4" aria-hidden />
        <span className="truncate font-mono text-xs font-medium">{target.id}</span>
      </header>

      <div className="min-h-0 flex-1 overflow-y-auto p-3 text-xs">
        {target.kind === 'item' ? (
          <ItemDetail item={target.item} />
        ) : target.kind === 'criterion' ? (
          <CriterionDetail criterion={target.criterion} />
        ) : (
          <RowDetail row={target.row} onGoToRecord={onGoToRecord} />
        )}
      </div>
    </section>
  )
}

/** The whole GWT, unclamped: reading it is the reason the panel exists. */
function CriterionDetail({ criterion }: { criterion: Criterion }) {
  return (
    <p className="leading-relaxed">
      <InlineMarkdown text={criterion.text} />
    </p>
  )
}

/**
 * The item's own text in full, then its criteria as a *list* — each of them is
 * a node of the sub-canvas, one click from its own full text (design-00002 §9).
 */
function ItemDetail({ item }: { item: RequirementItem }) {
  return (
    <>
      <p className="leading-relaxed">
        <InlineMarkdown text={item.text} />
      </p>
      <h3 className="text-muted-foreground mt-3 text-[11px] font-medium">{item.criteria.length} AC</h3>
      {item.criteria.length === 0 ? (
        <p className="text-muted-foreground mt-1">no AC</p>
      ) : (
        <ul aria-label={`Acceptance criteria of ${item.id}`} className="mt-1 space-y-1">
          {item.criteria.map((criterion) => (
            <li key={criterion.id} className="font-mono text-[11px]">
              {criterion.id}
            </li>
          ))}
        </ul>
      )}
    </>
  )
}

/** Which record ran which test, how it went, what it offers as proof, and the way back to it. */
function RowDetail({ row, onGoToRecord }: { row: AcceptanceRow; onGoToRecord: (recordId: string) => void }) {
  return (
    <>
      <dl className="space-y-2">
        <Field term="record">
          <span className="font-mono text-[11px]">{row.recordId}</span>
        </Field>
        <Field term="test">
          <InlineMarkdown text={row.test} />
        </Field>
        <Field term="result">
          <Badge variant="secondary" className="text-[10px]">
            {row.result}
          </Badge>
        </Field>
        {/* Absent, not empty: a checklist with no Evidence column has no such
            field, so the panel shows none (spec-00001-AC-37.8). */}
        {row.evidence === undefined ? null : (
          <Field term="evidence">
            <InlineMarkdown text={row.evidence} />
          </Field>
        )}
      </dl>
      <Button
        variant="outline"
        size="sm"
        className="mt-3 h-7 gap-1.5 text-xs"
        onClick={() => onGoToRecord(row.recordId)}
      >
        <ExternalLink className="size-3.5" aria-hidden />
        Go to {row.recordId}
      </Button>
    </>
  )
}

function Field({ term, children }: { term: string; children: ReactNode }) {
  return (
    <div>
      <dt className="text-muted-foreground text-[11px]">{term}</dt>
      <dd className="mt-0.5">{children}</dd>
    </div>
  )
}

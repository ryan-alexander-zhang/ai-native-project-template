import { Maximize2, PanelRight } from 'lucide-react'
import { useState } from 'react'
import type { DiagnosticKind, ItemsView, RequirementItem } from '../../src/requirements.ts'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { COVERAGE } from './coverageMarks.ts'
import { InlineMarkdown } from './InlineMarkdown.tsx'

/** What each diagnostic is, in the panel's own words (spec-00001-FR-40). */
const DIAGNOSTIC_LABEL: Record<DiagnosticKind, string> = {
  'item-shape': 'shape',
  'checklist-row': 'checklist row',
  unattributable: 'unattributable',
  // spec-00002-FR-7: the one kind read off the front matter, so its row carries
  // the field and the type instead of a source line.
  'relation-field': 'relation field',
}

/** The source line is evidence, not reading matter: enough of it to recognise the line. */
const LINE_LIMIT = 80

function truncate(text: string): string {
  return text.length > LINE_LIMIT ? `${text.slice(0, LINE_LIMIT)}…` : text
}

export interface InspectorProps {
  docId: string
  view: ItemsView
  /** The item the pointer or the keyboard is on, `undefined` when neither is (spec-00001-FR-34). */
  onInspect: (itemId?: string) => void
  /** Swap the canvas for this document's sub-canvas (spec-00001-FR-35). */
  onExpand: () => void
  /** The inline-id jump (spec-00001-FR-57): resolvable ids, and the way to their documents. */
  idOwners?: Record<string, string>
  onJump?: (docId: string) => void
}

/**
 * The inspector panel of spec-00001-FR-31 … FR-33: every requirement item the
 * selected spec or rule declares, in number order, with the coverage the
 * acceptance rows imply. It only reports what the server derived — the coverage
 * verdict is never recomputed here (design-00001 §2).
 */
export function Inspector({ docId, view, onInspect, onExpand, idOwners, onJump }: InspectorProps) {
  // An accordion, one row at a time (spec-00001-FR-38). The id is what is held,
  // not the item, so a refresh brings the same row back open — and resolving it
  // against the payload on every render is what closes the row when the item it
  // named has left the document (spec-00001-AC-38.5, AC-44.7).
  const [open, setOpen] = useState<string>()
  const expanded = view.items.some((item) => item.id === open) ? open : undefined
  const toggle = (itemId: string) => setOpen((current) => (current === itemId ? undefined : itemId))

  return (
    <section aria-label={`Requirements of ${docId}`} className="flex h-full min-h-0 flex-col">
      <header className="flex items-center gap-2 border-b px-3 py-2">
        <PanelRight className="size-4" aria-hidden />
        <span className="truncate font-mono text-xs font-medium">{docId}</span>
        <span className="text-muted-foreground ml-auto shrink-0 text-xs">{view.items.length} items</span>
        {/* A document with no items has no chain to walk, so the way down is
            closed rather than empty (spec-00001-AC-35.5). */}
        <Button
          variant="outline"
          size="sm"
          className="h-7 shrink-0 gap-1.5 text-xs"
          disabled={view.items.length === 0}
          onClick={onExpand}
        >
          <Maximize2 className="size-3.5" aria-hidden />
          Expand as sub-canvas
        </Button>
      </header>

      <div className="min-h-0 flex-1 overflow-y-auto">
        {view.items.length === 0 ? (
          <p className="text-muted-foreground p-3 text-xs">no requirement items</p>
        ) : (
          <ul aria-label={`Requirement items of ${docId}`}>
            {view.items.map((item) => {
              const { Icon, label, token } = COVERAGE[item.coverage]
              return (
                // Hover and keyboard focus are one path: FR-34 gives them equal
                // standing, so the row is focusable and both events lead here.
                // Click and Enter are the other path — reading, not linking
                // (spec-00001-FR-38); the two gestures do not collide.
                <li
                  key={item.id}
                  data-testid={`item-${item.id}`}
                  tabIndex={0}
                  aria-expanded={expanded === item.id}
                  onMouseEnter={() => onInspect(item.id)}
                  onMouseLeave={() => onInspect(undefined)}
                  onFocus={() => onInspect(item.id)}
                  onBlur={() => onInspect(undefined)}
                  onClick={() => toggle(item.id)}
                  onKeyDown={(event) => {
                    if (event.key !== 'Enter') return
                    event.preventDefault()
                    toggle(item.id)
                  }}
                  className="hover:bg-accent focus:bg-accent cursor-pointer border-b px-3 py-2 outline-none"
                >
                  <div className="flex items-center gap-2">
                    <Icon role="img" aria-label={label} className="size-3.5 shrink-0" style={{ color: token }} />
                    <span className="truncate font-mono text-xs">{item.id}</span>
                    <Badge variant="secondary" className="ml-auto shrink-0 text-[10px]">
                      {item.criteria.length} AC
                    </Badge>
                  </div>
                  {/* Two lines and no more until the row is opened; the clamp
                      falls on the rendered text, never on the source
                      (spec-00001-FR-39, design-00002 §9). */}
                  <p
                    className={`text-muted-foreground mt-1 text-xs ${expanded === item.id ? '' : 'line-clamp-2'}`}
                  >
                    <InlineMarkdown text={item.text} idOwners={idOwners} onJump={onJump} />
                  </p>
                  {expanded === item.id ? <Expansion item={item} idOwners={idOwners} onJump={onJump} /> : null}
                </li>
              )
            })}
          </ul>
        )}

        {/*
          Data that broke rather than data that is uncovered, so it sits after
          the list and takes --destructive, not a coverage token. The region
          took in the grammar diagnostics in the sixth round, which is all the
          rename says (spec-00001-FR-33, FR-40; design-00002 §9).
        */}
        {view.diagnostics.length === 0 ? null : (
          <section aria-label={`Parse diagnostics of ${docId}`} className="border-t p-3">
            <h3 className="text-destructive text-xs font-medium">parse diagnostics</h3>
            <ul className="mt-1 space-y-1">
              {view.diagnostics.map((entry, index) => (
                <li
                  // Two rows can name the same id from the same record, so the
                  // position in the list is the only thing that tells them apart.
                  key={`${entry.kind}-${entry.recordId ?? docId}-${entry.declaredId ?? index}-${index}`}
                  className="text-destructive flex items-baseline gap-2 font-mono text-[11px]"
                >
                  <span className="shrink-0 truncate">{entry.recordId ?? docId}</span>
                  <span aria-hidden>·</span>
                  <span className="shrink-0">{DIAGNOSTIC_LABEL[entry.kind]}</span>
                  <span aria-hidden>·</span>
                  {entry.declaredId === undefined ? null : <span className="truncate">{entry.declaredId}</span>}
                  {entry.attributedTo === undefined ? null : (
                    <span className="truncate">→ {entry.attributedTo}</span>
                  )}
                  {entry.text === undefined ? null : (
                    <span className="text-muted-foreground truncate">{truncate(entry.text)}</span>
                  )}
                </li>
              ))}
            </ul>
          </section>
        )}
      </div>
    </section>
  )
}

/**
 * The opened row (spec-00001-FR-38): every criterion in full, because the panel
 * has no AC node to click — that is the sub-canvas's job (design-00002 §9). An
 * item with no criteria says so rather than opening onto nothing.
 */
function Expansion({
  item,
  idOwners,
  onJump,
}: {
  item: RequirementItem
  idOwners?: Record<string, string>
  onJump?: (docId: string) => void
}) {
  return (
    <div aria-label={`Expanded ${item.id}`} className="mt-2 space-y-2 border-l pl-3">
      {item.criteria.length === 0 ? (
        <p className="text-muted-foreground text-xs">no AC</p>
      ) : (
        item.criteria.map((criterion) => (
          <div key={criterion.id}>
            <p className="font-mono text-[11px]">{criterion.id}</p>
            <p className="text-muted-foreground text-xs">
              <InlineMarkdown text={criterion.text} idOwners={idOwners} onJump={onJump} />
            </p>
          </div>
        ))
      )}
    </div>
  )
}

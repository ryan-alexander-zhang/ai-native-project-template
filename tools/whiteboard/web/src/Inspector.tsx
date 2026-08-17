import { Maximize2, PanelRight } from 'lucide-react'
import type { ItemsView } from '../../src/requirements.ts'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { COVERAGE } from './coverageMarks.ts'

export interface InspectorProps {
  docId: string
  view: ItemsView
  /** The item the pointer or the keyboard is on, `undefined` when neither is (spec-00001-FR-34). */
  onInspect: (itemId?: string) => void
  /** Swap the canvas for this document's sub-canvas (spec-00001-FR-35). */
  onExpand: () => void
}

/**
 * The inspector panel of spec-00001-FR-31 … FR-33: every requirement item the
 * selected spec or rule declares, in number order, with the coverage the
 * acceptance rows imply. It only reports what the server derived — the coverage
 * verdict is never recomputed here (design-00001 §2).
 */
export function Inspector({ docId, view, onInspect, onExpand }: InspectorProps) {
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
                <li
                  key={item.id}
                  data-testid={`item-${item.id}`}
                  tabIndex={0}
                  onMouseEnter={() => onInspect(item.id)}
                  onMouseLeave={() => onInspect(undefined)}
                  onFocus={() => onInspect(item.id)}
                  onBlur={() => onInspect(undefined)}
                  className="hover:bg-accent focus:bg-accent border-b px-3 py-2 outline-none"
                >
                  <div className="flex items-center gap-2">
                    <Icon role="img" aria-label={label} className="size-3.5 shrink-0" style={{ color: token }} />
                    <span className="truncate font-mono text-xs">{item.id}</span>
                    <Badge variant="secondary" className="ml-auto shrink-0 text-[10px]">
                      {item.criteria.length} AC
                    </Badge>
                  </div>
                  {/* Two lines and no more; the full text is read in the editor
                      or the sub-canvas, not in a tooltip (design-00002 §9). */}
                  <p className="text-muted-foreground mt-1 line-clamp-2 text-xs">{item.text}</p>
                </li>
              )
            })}
          </ul>
        )}

        {/*
          Data that broke rather than data that is uncovered, so it sits after
          the list and takes --destructive, not a coverage token
          (spec-00001-FR-33).
        */}
        {view.unattributed.length === 0 ? null : (
          <section aria-label={`Unattributable entries of ${docId}`} className="border-t p-3">
            <h3 className="text-destructive text-xs font-medium">unattributable</h3>
            <ul className="mt-1 space-y-1">
              {view.unattributed.map((entry) => (
                <li
                  key={`${entry.recordId ?? docId}-${entry.declaredId}`}
                  className="text-destructive flex items-baseline gap-2 font-mono text-[11px]"
                >
                  <span className="truncate">{entry.recordId ?? docId}</span>
                  <span aria-hidden>·</span>
                  <span className="truncate">{entry.declaredId}</span>
                  {entry.attributedTo === undefined ? null : (
                    <span className="truncate">→ {entry.attributedTo}</span>
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

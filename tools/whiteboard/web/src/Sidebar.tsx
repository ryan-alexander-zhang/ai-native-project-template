import { ChevronRight, TriangleAlert } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { readCollapsed, writeCollapsed } from './sidebar.ts'
import type { TypeGroup } from './sidebarModel.ts'
import { statusColour, statusLabel, typeIcon } from './status.ts'

export interface SidebarProps {
  /** Every document on the board, grouped and ordered as the canvas columns are. */
  groups: TypeGroup[]
  /** The board's selection, which is what a row is highlighted by (spec-00008-FR-3). */
  selected?: string
  /** Going to a document — `focus` and nothing else (spec-00008-FR-2). */
  onPick: (id: string) => void
}

/**
 * The navigation sidebar (spec-00008): every document on the board as a list of
 * type groups, in the canvas's own order. A row goes to its document through the
 * board's one jump path, so a row whose document has left the board refuses
 * exactly as the command palette's pick does (spec-00008-FR-8).
 *
 * Collapsed groups live in the browser, so the same groups are shut on the next
 * open (design-00002 §17.1).
 */
export function Sidebar({ groups, selected, onPick }: SidebarProps) {
  const [collapsed, setCollapsed] = useState<string[]>(readCollapsed)
  const selectedRow = useRef<HTMLButtonElement>(null)

  function toggle(key: string) {
    const next = collapsed.includes(key) ? collapsed.filter((one) => one !== key) : [...collapsed, key]
    writeCollapsed(next)
    setCollapsed(next)
  }

  // A **change** of selection opens the group it landed in — and only a change:
  // collapsing the group of the selected row afterwards leaves it collapsed
  // (spec-00008-AC-4.5). The sidebar is unmounted while it is put away, so the
  // same effect on mount is what makes reopening catch up (spec-00008-AC-3.4).
  useEffect(() => {
    const group = groups.find((one) => one.nodes.some((node) => node.id === selected))
    if (group === undefined || !collapsed.includes(group.key)) return
    const next = collapsed.filter((one) => one !== group.key)
    writeCollapsed(next)
    setCollapsed(next)
    // Only the selection may reopen a group, so the effect watches nothing else.
  }, [selected])

  // …and then the row is scrolled to, once the expansion above has drawn it.
  useEffect(() => {
    selectedRow.current?.scrollIntoView({ block: 'nearest' })
  }, [selected, collapsed])

  return (
    <nav aria-label="Documents" className="h-full overflow-y-auto p-2">
      {groups.map((group) => {
        const Icon = typeIcon(group.key)
        const open = !collapsed.includes(group.key)
        return (
          <div key={group.key}>
            <Button
              variant="ghost"
              size="sm"
              className="w-full justify-start gap-2 px-2"
              aria-expanded={open}
              onClick={() => toggle(group.key)}
            >
              <ChevronRight className={`size-3 transition-transform ${open ? 'rotate-90' : ''}`} aria-hidden />
              <Icon className="size-4" aria-hidden />
              {group.type}
              <Badge variant="secondary" className="ml-auto">
                {group.nodes.length}
              </Badge>
            </Button>
            {open
              ? group.nodes.map((node) => (
                  <Button
                    key={node.path}
                    ref={node.id === selected ? selectedRow : undefined}
                    variant="ghost"
                    className={`h-auto w-full flex-col items-stretch gap-0.5 py-1.5 pr-2 pl-4 font-normal ${
                      node.id === selected ? 'bg-accent' : ''
                    }`}
                    // Not colour alone: the highlighted row says what it is
                    // (design-00002 §17.5).
                    aria-current={node.id === selected ? 'true' : undefined}
                    onClick={() => onPick(node.id)}
                  >
                    <span className="flex items-center gap-1.5">
                      <span
                        className="size-2 shrink-0 rounded-full"
                        style={{ backgroundColor: statusColour(node) }}
                        aria-hidden
                      />
                      <span className="truncate font-mono text-xs">{node.id}</span>
                      {/* An id two documents declare is not a key: the row is
                          the path, and the id it collides on sits beside it
                          (spec-00002-FR-8, design-00002 §4). */}
                      {node.duplicateOf === undefined ? null : (
                        <span className="text-muted-foreground shrink-0 font-mono text-[10px]">
                          {node.duplicateOf}
                        </span>
                      )}
                      <span className="text-muted-foreground ml-auto flex shrink-0 items-center gap-1 text-[10px]">
                        {node.ok ? null : <TriangleAlert className="size-3" aria-hidden />}
                        {statusLabel(node)}
                      </span>
                    </span>
                    <span className="truncate text-left text-xs">{node.title}</span>
                  </Button>
                ))
              : null}
          </div>
        )
      })}
    </nav>
  )
}

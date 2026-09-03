import { ChevronRight, Folder, TriangleAlert } from 'lucide-react'
import { type RefObject, useEffect, useRef, useState } from 'react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import type { DocNode } from '../../src/docRepository.ts'
import { readExpanded, writeExpanded } from './sidebar.ts'
import type { TypeGroup } from './sidebarModel.ts'
import { statusColour, statusLabel, typeIcon } from './status.ts'

export interface SidebarProps {
  /** Every document on the board, grouped and ordered as the canvas columns are. */
  groups: TypeGroup[]
  /** The board's selection, which is what a row is highlighted by (spec-00008-FR-3). */
  selected?: string
  /**
   * The open directory groups, by expand key: the board's own set, the very one
   * the canvas draws from, so opening a group in either place opens it in both
   * (spec-00010-FR-8, design-00002 §19.3).
   */
  expandedGroups: string[]
  /** Open or close one directory group — the canvas group node's own act (spec-00010-FR-6). */
  onToggleGroup: (expandKey: string) => void
  /** Going to a document — `focus` and nothing else (spec-00008-FR-2). */
  onPick: (id: string) => void
}

/**
 * One level of the tree, as the row's own left padding: the level times
 * `--tree-indent` and nothing else, so the levels can never drift from each
 * other the way three hand-written paddings did (design-00002 §17.2,
 * issue-00026).
 */
function indentOf(level: number) {
  return { paddingLeft: `calc(var(--tree-indent) * ${level})` }
}

interface RowProps {
  node: DocNode
  selected?: string
  /** This row's depth in the tree: 1 for a top-level document, 2 for a directory group's member. */
  level: number
  rowRef?: RefObject<HTMLButtonElement | null>
  onPick: (id: string) => void
}

/** One document's row (design-00002 §17.2), the same at either indentation level. */
function Row({ node, selected, level, rowRef, onPick }: RowProps) {
  return (
    <Button
      ref={rowRef}
      variant="ghost"
      className={`h-auto w-full items-start justify-start gap-2 py-1.5 pr-2 font-normal ${
        node.id === selected ? 'bg-accent' : ''
      }`}
      data-level={level}
      style={indentOf(level)}
      // Not colour alone: the highlighted row says what it is
      // (design-00002 §17.5).
      aria-current={node.id === selected ? 'true' : undefined}
      onClick={() => onPick(node.id)}
    >
      {/* The two fixed 16px columns every row opens with, a header's included:
          the fold column, which a leaf row keeps empty, and the icon column,
          where the status dot goes. A document row's text therefore starts
          exactly where a sibling header's name starts, and one level down is one
          indent along and nothing else (design-00002 §17.2). */}
      <span className="h-4 w-4 shrink-0" />
      <span className="flex h-4 w-4 shrink-0 items-center justify-center">
        <span className="size-2 shrink-0 rounded-full" style={{ backgroundColor: statusColour(node) }} aria-hidden />
      </span>
      <span className="flex min-w-0 flex-1 flex-col items-stretch gap-0.5">
        <span className="flex items-center gap-1.5">
          <span className="truncate font-mono text-xs">{node.id}</span>
          {/* An id two documents declare is not a key: the row is the path, and
              the id it collides on sits beside it (spec-00002-FR-8,
              design-00002 §4). */}
          {node.duplicateOf === undefined ? null : (
            <span className="text-muted-foreground shrink-0 font-mono text-[10px]">{node.duplicateOf}</span>
          )}
          <span className="text-muted-foreground ml-auto flex shrink-0 items-center gap-1 text-[10px]">
            {node.ok ? null : <TriangleAlert className="size-3" aria-hidden />}
            {statusLabel(node)}
          </span>
        </span>
        <span className="truncate text-left text-xs">{node.title}</span>
      </span>
    </Button>
  )
}

/**
 * The navigation sidebar (spec-00008): every document on the board as a list of
 * type groups, in the canvas's own order — and within a type group, the
 * top-level documents and then the directory groups, exactly as the column is
 * drawn (spec-00010-FR-8, design-00002 §19.4). A row goes to its document
 * through the board's one jump path, so a row whose document has left the board
 * refuses exactly as the command palette's pick does (spec-00008-FR-8).
 *
 * Expanded type groups live in the browser, so the same groups are open on the
 * next open; with no key every group is collapsed and the first screen is the
 * type groups alone (design-00002 §17.1, spec-00008-AC-4.3). The directory
 * groups' expanded state is **not** kept here: it is the board's, shared with
 * the canvas.
 */
export function Sidebar({ groups, selected, expandedGroups, onToggleGroup, onPick }: SidebarProps) {
  const [expanded, setExpanded] = useState<string[]>(readExpanded)
  const selectedRow = useRef<HTMLButtonElement>(null)

  function toggle(key: string) {
    const next = expanded.includes(key) ? expanded.filter((one) => one !== key) : [...expanded, key]
    writeExpanded(next)
    setExpanded(next)
  }

  // A **change** of selection opens the group it landed in — and only a change:
  // collapsing the group of the selected row afterwards leaves it collapsed
  // (spec-00008-AC-4.5). The sidebar is unmounted while it is put away, so the
  // same effect on mount is what makes reopening catch up (spec-00008-AC-3.4).
  // Only the type group: the directory group the selection landed in is opened
  // by `useBoard`, for the canvas and this list at once (design-00002 §19.3).
  useEffect(() => {
    const group = groups.find((one) => one.nodes.some((node) => node.id === selected))
    if (group === undefined || expanded.includes(group.key)) return
    const next = [...expanded, group.key]
    writeExpanded(next)
    setExpanded(next)
    // Only the selection may reopen a group, so the effect watches nothing else.
  }, [selected])

  // …and then the row is scrolled to, once the expansion above has drawn it —
  // the directory group's expansion included, which is why it is a dependency
  // (spec-00010-AC-8.4).
  useEffect(() => {
    selectedRow.current?.scrollIntoView({ block: 'nearest' })
  }, [selected, expanded, expandedGroups])

  return (
    <nav aria-label="Documents" className="h-full overflow-y-auto p-2">
      {groups.map((group) => {
        const Icon = typeIcon(group.key)
        const open = expanded.includes(group.key)
        return (
          <div key={group.key}>
            <Button
              variant="ghost"
              size="sm"
              className="w-full justify-start gap-2 pr-2"
              data-level={0}
              style={indentOf(0)}
              aria-expanded={open}
              onClick={() => toggle(group.key)}
            >
              <ChevronRight className={`size-4 transition-transform ${open ? 'rotate-90' : ''}`} aria-hidden />
              <Icon className="size-4" aria-hidden />
              <span className="truncate">{group.type}</span>
              <Badge variant="secondary" className="ml-auto">
                {group.nodes.length}
              </Badge>
            </Button>
            {open ? (
              <>
                {group.top.map((node) => (
                  <Row
                    key={node.path}
                    node={node}
                    selected={selected}
                    level={1}
                    rowRef={node.id === selected ? selectedRow : undefined}
                    onPick={onPick}
                  />
                ))}
                {group.directories.map((directory) => {
                  const openDirectory = expandedGroups.includes(directory.expandKey)
                  return (
                    <div key={directory.expandKey}>
                      {/* The directory group's header, one level in from the type
                          group's, and the same syntax: the fold chevron in the
                          leading slot, the count at the right edge. `Folder` is
                          a shape, not a term — the vocabulary is still
                          «directory group» (design-00002 §19.2, §19.4). */}
                      <Button
                        variant="ghost"
                        size="sm"
                        className="w-full justify-start gap-2 pr-2"
                        data-level={1}
                        style={indentOf(1)}
                        aria-expanded={openDirectory}
                        onClick={() => onToggleGroup(directory.expandKey)}
                      >
                        <ChevronRight
                          className={`size-4 transition-transform ${openDirectory ? 'rotate-90' : ''}`}
                          aria-hidden
                        />
                        <Folder className="size-4" aria-hidden />
                        <span className="truncate">{directory.name}</span>
                        <Badge variant="secondary" className="ml-auto">
                          {directory.nodes.length}
                        </Badge>
                      </Button>
                      {openDirectory
                        ? directory.nodes.map((node) => (
                            <Row
                              key={node.path}
                              node={node}
                              selected={selected}
                              level={2}
                              rowRef={node.id === selected ? selectedRow : undefined}
                              onPick={onPick}
                            />
                          ))
                        : null}
                    </div>
                  )
                })}
              </>
            ) : null}
          </div>
        )
      })}
    </nav>
  )
}

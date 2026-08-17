import { Background, Controls, NodeToolbar, Position, ReactFlow, ReactFlowProvider, useReactFlow } from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { FileQuestionMark, LayoutDashboard, Search, TriangleAlert } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { Toaster } from 'sonner'
import type { DocNode } from '../../src/docRepository.ts'
import { Badge } from '@/components/ui/badge'
import {
  Breadcrumb,
  BreadcrumbItem,
  BreadcrumbLink,
  BreadcrumbList,
  BreadcrumbPage,
  BreadcrumbSeparator,
} from '@/components/ui/breadcrumb'
import { Button } from '@/components/ui/button'
import { ResizableHandle, ResizablePanel, ResizablePanelGroup } from '@/components/ui/resizable'
import { useDefaultLayout } from 'react-resizable-panels'
import { TooltipProvider } from '@/components/ui/tooltip'
import { api } from './api.ts'
import { CommandPalette } from './CommandPalette.tsx'
import { Editor } from './Editor.tsx'
import { Inspector } from './Inspector.tsx'
import { NODE_HEIGHT, NODE_WIDTH } from './layout.ts'
import { NodeCard } from './NodeCard.tsx'
import { AcceptanceRowNode, CriterionNode, ItemNode } from './SubNodes.tsx'
import { Terminal } from './Terminal.tsx'
import { ThemeMenu } from './ThemeMenu.tsx'
import { Toolbar } from './Toolbar.tsx'
import { evidenceOf, relationsOf, suppressedNodes, toFlowEdges, toFlowNodes } from './canvasModel.ts'
import { onFlowError } from './flowError.ts'
import { subCanvas } from './subCanvas.ts'
import { useTheme } from './theme.ts'
import { useBoard } from './useBoard.ts'

type DocNodeData = { node: DocNode; kind?: string; suppressed?: boolean }

const nodeTypes = {
  doc: ({ data, selected }: { data: DocNodeData; selected?: boolean }) => (
    <NodeCard node={data.node} kind={data.kind} selected={selected ?? false} suppressed={data.suppressed} />
  ),
  item: ItemNode,
  criterion: CriterionNode,
  acceptanceRow: AcceptanceRowNode,
}

function Canvas() {
  const board = useBoard()
  const theme = useTheme()
  const { fitView, setCenter } = useReactFlow()
  const [searching, setSearching] = useState(false)
  const [inspecting, setInspecting] = useState<string>()
  // The document whose sub-canvas has taken the canvas over (spec-00001-FR-35);
  // `undefined` is the top-level board.
  const [drilled, setDrilled] = useState<string>()
  // v4 has no autoSaveId; this hook is the persistence path (localStorage by default).
  const rows = useDefaultLayout({ id: 'whiteboard-rows', panelIds: ['work', 'terminal'] })
  const columns = useDefaultLayout({ id: 'whiteboard-columns', panelIds: ['canvas', 'editor'] })
  // The inspector shares the slot but not the width: each is remembered on its
  // own, so opening one does not resize the other (design-00002 §9).
  const inspectorColumns = useDefaultLayout({
    id: 'whiteboard-inspector-columns',
    panelIds: ['canvas', 'inspector'],
  })

  const nodes = useMemo(() => {
    const laid = toFlowNodes(board.graph, board.placed, board.selected)
    const suppressed = suppressedNodes(board.graph, board.selected)
    return laid.map((node) => {
      const data = node.data as DocNodeData
      return {
        ...node,
        data: { ...data, kind: board.kinds[data.node.type ?? ''], suppressed: suppressed.has(node.id) },
      }
    })
  }, [board.graph, board.placed, board.selected, board.kinds])

  // Hovering a panel row asks "where is this item's evidence": the records that
  // verified it, and the AC ids they cited (spec-00001-FR-34).
  const evidence = useMemo(() => {
    const item = board.items?.items.find((candidate) => candidate.id === inspecting)
    return item ? evidenceOf(item) : undefined
  }, [board.items, inspecting])

  const edges = useMemo(
    () => toFlowEdges(board.graph, board.placed, board.selected, evidence),
    [board.graph, board.placed, board.selected, evidence],
  )
  const selected = board.selectedNode
  // The dataset the one React Flow instance is showing: the document graph, or
  // the drilled document's verification chain (spec-00001-FR-35).
  const sub = useMemo(
    () => (drilled !== undefined && board.items !== undefined ? subCanvas(board.items) : undefined),
    [drilled, board.items],
  )
  // Editor first: the panel follows the selection, but a deliberate act of
  // editing is not interrupted by one click on the canvas (spec-00001-FR-31).
  // The sub-canvas is the panel's own expansion, so it takes the whole width.
  const inspector =
    board.editing === undefined && selected !== undefined && sub === undefined ? board.items : undefined

  // A new dataset lands under the old viewport, which may be nowhere near it.
  useEffect(() => {
    if (drilled !== undefined) void fitView()
  }, [drilled, fitView])

  /**
   * Centre the viewport on a document node and select it (spec-00001-FR-27).
   * Going to a document is a top-level act, so it also leaves any sub-canvas —
   * which is what the breadcrumb's «Board» does (spec-00001-FR-36).
   */
  function focus(id: string) {
    setDrilled(undefined)
    const at = board.placed.find((position) => position.id === id)
    if (at) setCenter(at.x + NODE_WIDTH / 2, at.y + NODE_HEIGHT / 2, { zoom: 1, duration: 300 })
    void board.select(id)
  }

  return (
    <div className="flex h-screen flex-col">
      <header className="flex items-center gap-3 border-b px-4 py-2">
        <LayoutDashboard className="size-5" aria-hidden />
        {/* The breadcrumb takes the title's place, and only in a sub-canvas:
            the top-level board is not a step of any trail (spec-00001-AC-35.6). */}
        {drilled === undefined ? (
          <strong className="text-sm">docs whiteboard</strong>
        ) : (
          <Breadcrumb>
            <BreadcrumbList className="text-sm">
              <BreadcrumbItem>
                <BreadcrumbLink asChild>
                  <button type="button" className="cursor-pointer" onClick={() => focus(drilled)}>
                    Board
                  </button>
                </BreadcrumbLink>
              </BreadcrumbItem>
              {/* A slash, not the default chevron: the trail reads
                  «Board / <document id>» (spec-00001-AC-35.4). */}
              <BreadcrumbSeparator>/</BreadcrumbSeparator>
              <BreadcrumbItem>
                <BreadcrumbPage className="font-mono text-xs">{drilled}</BreadcrumbPage>
              </BreadcrumbItem>
            </BreadcrumbList>
          </Breadcrumb>
        )}

        <Button
          variant="outline"
          size="sm"
          className="text-muted-foreground ml-2 gap-2 font-normal"
          onClick={() => setSearching(true)}
        >
          <Search className="size-4" aria-hidden />
          Find a document
          <kbd className="bg-muted rounded px-1.5 py-0.5 font-mono text-[10px]">⌘K</kbd>
        </Button>

        <div className="ml-auto flex items-center gap-2">
          {board.graph.issues.length === 0 ? (
            <span className="text-muted-foreground text-xs">no issues</span>
          ) : (
            <Badge variant="destructive" className="gap-1 text-xs">
              <TriangleAlert className="size-3" aria-hidden />
              {board.graph.issues.length} issues
            </Badge>
          )}
          <ThemeMenu theme={theme.theme} onChoose={theme.choose} />
        </div>
      </header>

      <ResizablePanelGroup orientation="vertical" {...rows} className="min-h-0 flex-1">
        <ResizablePanel id="work" defaultSize={65} minSize={25}>
          <ResizablePanelGroup orientation="horizontal" {...(inspector ? inspectorColumns : columns)}>
            <ResizablePanel id="canvas" defaultSize={board.editing || inspector ? 62 : 100} minSize={30}>
              <div className="relative h-full">
                <ReactFlow
                  nodes={sub ? sub.nodes : nodes}
                  edges={sub ? sub.edges : edges}
                  nodeTypes={nodeTypes}
                  // A sub-canvas node is not a document: selecting is the top
                  // level's act, and so is dropping the selection — losing it
                  // here would take the items the sub-canvas is drawn from.
                  onNodeClick={sub ? undefined : (_event, node) => void board.select(node.id)}
                  onPaneClick={sub ? undefined : board.deselect}
                  // Handles exist to anchor edges, not to draw them: every edge
                  // comes from front matter (spec-00001-AC-1.14).
                  nodesConnectable={false}
                  onError={onFlowError}
                  fitView
                >
                  <Background />
                  <Controls />
                  {/* The sub-canvas is read-only: no editing, no review, no
                      transition, and no document node to hang them on. */}
                  {selected && sub === undefined ? (
                    <NodeToolbar nodeId={selected.id} isVisible position={Position.Top}>
                      <Toolbar
                        node={selected}
                        transitions={board.transitions}
                        nextSteps={board.nextSteps}
                        relations={relationsOf(board.graph, selected.id, board.relationOrder)}
                        onPickRelation={focus}
                        onEdit={() => board.setEditing(selected.id)}
                        onStatus={(to) => void board.run(() => api.setStatus(selected.id, to))}
                        onAccept={() => void board.run(() => api.accept(selected.id))}
                        onClarify={(questions) => void board.run(() => api.clarify(selected.id, questions))}
                        onAdvance={(targetType) => void board.advance(selected.id, targetType)}
                      />
                    </NodeToolbar>
                  ) : null}
                </ReactFlow>

                {board.graph.nodes.length === 0 ? (
                  <div className="text-muted-foreground pointer-events-none absolute inset-0 flex flex-col items-center justify-center gap-2">
                    <FileQuestionMark className="size-8" aria-hidden />
                    <p className="text-sm">no documents under docs/ yet</p>
                  </div>
                ) : null}
              </div>
            </ResizablePanel>

            {board.editing ? (
              <>
                <ResizableHandle withHandle />
                <ResizablePanel id="editor" defaultSize={38} minSize={20}>
                  <Editor
                    docId={board.editing}
                    onSaved={() => void board.refresh()}
                    onClose={() => board.setEditing(undefined)}
                  />
                </ResizablePanel>
              </>
            ) : inspector && selected ? (
              <>
                <ResizableHandle withHandle />
                <ResizablePanel id="inspector" defaultSize={38} minSize={20}>
                  <Inspector
                    docId={selected.id}
                    view={inspector}
                    onInspect={setInspecting}
                    onExpand={() => setDrilled(selected.id)}
                  />
                </ResizablePanel>
              </>
            ) : null}
          </ResizablePanelGroup>
        </ResizablePanel>

        {board.terminalOpen ? (
          <>
            <ResizableHandle withHandle />
            <ResizablePanel id="terminal" defaultSize={35} minSize={15}>
              <Terminal session={board.session} dark={theme.isDark} onClose={() => board.setTerminalOpen(false)} />
            </ResizablePanel>
          </>
        ) : null}
      </ResizablePanelGroup>

      <CommandPalette
        nodes={board.graph.nodes}
        open={searching}
        onOpenChange={setSearching}
        onPick={(id) => {
          setSearching(false)
          focus(id)
        }}
      />
      <Toaster position="bottom-right" theme={theme.isDark ? 'dark' : 'light'} richColors closeButton />
    </div>
  )
}

export function Board() {
  return (
    <ReactFlowProvider>
      <TooltipProvider>
        <Canvas />
      </TooltipProvider>
    </ReactFlowProvider>
  )
}

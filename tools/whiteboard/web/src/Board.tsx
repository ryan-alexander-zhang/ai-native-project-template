import {
  Background,
  Controls,
  type Node as FlowNode,
  NodeToolbar,
  Position,
  ReactFlow,
  ReactFlowProvider,
  useReactFlow,
  useStore,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { FileQuestionMark, FileWarning, LayoutDashboard, Search, TriangleAlert } from 'lucide-react'
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
import { Details } from './Details.tsx'
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
import { detailTarget, subCanvas } from './subCanvas.ts'
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

/** React Flow's own floor, which is what the top-level graph wants. */
const DEFAULT_MIN_ZOOM = 0.5

/**
 * `fitView` clamps to `minZoom`, and a sub-canvas is as tall as the document
 * has acceptance rows: under the default floor, fitting a few dozen items is
 * arithmetically impossible and the view opens clamped mid-chain
 * (spec-00001-AC-35.7, record-00004 observation 3). So the floor drops to half
 * of what *this* sub-canvas needs — low enough that the floor never decides the
 * fit, and no lower. The top-level graph keeps the default: a floor below it
 * buys nothing there and lets the whole board be zoomed down to a grey smudge.
 */
function minZoomFor(sub: { nodes: FlowNode[] }, width: number, height: number): number {
  if (sub.nodes.length === 0 || width === 0 || height === 0) return DEFAULT_MIN_ZOOM
  const right = Math.max(...sub.nodes.map((node) => node.position.x + (node.width ?? 0)))
  const bottom = Math.max(...sub.nodes.map((node) => node.position.y + (node.height ?? 0)))
  return Math.min(DEFAULT_MIN_ZOOM, Math.min(width / right, height / bottom) / 2)
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
  // The sub-canvas node whose detail is open (spec-00001-FR-37), held by id so
  // a graph refresh keeps the same node's detail open.
  const [detail, setDetail] = useState<string>()
  // A document the board was told to go to, carried with the canvas width that
  // was current when it was asked for: the slot has settled once React Flow
  // reports a different one (issue-00006).
  const [pendingFocus, setPendingFocus] = useState<{ id: string; width: number }>()
  // React Flow's own measurement of the canvas, which is the width `setCenter`
  // and `fitView` divide by. It lands a frame after the panel mounts, so it —
  // not the commit that mounted the panel — is the signal that the layout is
  // done (issue-00006).
  const canvasWidth = useStore((state) => state.width)
  const canvasHeight = useStore((state) => state.height)
  // v4 has no autoSaveId; this hook is the persistence path (localStorage by default).
  const rows = useDefaultLayout({ id: 'whiteboard-rows', panelIds: ['work', 'terminal'] })
  const columns = useDefaultLayout({ id: 'whiteboard-columns', panelIds: ['canvas', 'editor'] })
  // The inspector shares the slot but not the width: each is remembered on its
  // own, so opening one does not resize the other (design-00002 §9).
  const inspectorColumns = useDefaultLayout({
    id: 'whiteboard-inspector-columns',
    panelIds: ['canvas', 'inspector'],
  })
  // The detail panel is a third occupant of the slot, and its width is its own
  // (design-00002 §9).
  const detailColumns = useDefaultLayout({
    id: 'whiteboard-detail-columns',
    panelIds: ['canvas', 'detail'],
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
  // The floor follows the dataset: loose enough for the sub-canvas on show,
  // React Flow's default on the top-level board.
  const minZoom = useMemo(
    () => (sub === undefined ? DEFAULT_MIN_ZOOM : minZoomFor(sub, canvasWidth, canvasHeight)),
    [sub, canvasWidth, canvasHeight],
  )
  // Editor first: the panel follows the selection, but a deliberate act of
  // editing is not interrupted by one click on the canvas (spec-00001-FR-31).
  // The sub-canvas is the panel's own expansion, so it takes the whole width.
  const inspector =
    board.editing === undefined && selected !== undefined && sub === undefined ? board.items : undefined
  // Resolved from the current payload, so a refresh keeps it by id and the
  // disappearance of what it pointed at closes it (plan-00006 U2).
  const shown = useMemo(() => {
    if (sub === undefined || board.items === undefined || detail === undefined) return undefined
    return detailTarget(board.items, detail)
  }, [sub, board.items, detail])

  // A new dataset lands under the old viewport, which may be nowhere near it.
  // React Flow fits the nodes it has *measured*, and a dataset swapped in this
  // tick has none — the fit then lands on whatever was left over. The
  // sub-canvas fixes every node's geometry itself, so we tell React Flow to fit
  // from the declared sizes instead of the measured ones (spec-00001-AC-35.7).
  useEffect(() => {
    if (drilled !== undefined) void fitView({ includeHiddenNodes: true })
  }, [drilled, fitView])

  // The document the sub-canvas was drawn from can leave the board under us;
  // there is nothing to be inside of, so we come back up (plan-00006 U2).
  useEffect(() => {
    if (drilled !== undefined && !board.graph.nodes.some((node) => node.id === drilled)) setDrilled(undefined)
  }, [board.graph, drilled])

  // Esc closes the detail and leaves the sub-canvas standing (spec-00001-AC-37.7).
  useEffect(() => {
    if (shown === undefined) return
    const close = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setDetail(undefined)
    }
    window.addEventListener('keydown', close)
    return () => window.removeEventListener('keydown', close)
  }, [shown])

  function centre(id: string) {
    const at = board.placed.find((position) => position.id === id)
    if (at) setCenter(at.x + NODE_WIDTH / 2, at.y + NODE_HEIGHT / 2, { zoom: 1, duration: 300 })
  }

  // The centring above ran against the full canvas; the inspector then takes a
  // third of it, which leaves the node — and the right end of its floating
  // toolbar — under the panel's edge. Centre again once the canvas has actually
  // narrowed: the panel's mount and React Flow's new width are different
  // frames, and only the second is a settled layout — waiting on the first
  // divides by the width the panel has already taken away (issue-00006,
  // design-00002 §9, record-00004 observation 4). A selection that leaves the
  // width alone never gets here, so the viewport only moves when the slot moved.
  useEffect(() => {
    if (pendingFocus === undefined || selected?.id !== pendingFocus.id) return
    if (canvasWidth === pendingFocus.width) return
    centre(pendingFocus.id)
    setPendingFocus(undefined)
  }, [pendingFocus, canvasWidth, selected, board.placed])

  /**
   * Centre the viewport on a document node and select it (spec-00001-FR-27).
   * Going to a document is a top-level act, so it also leaves any sub-canvas
   * and its detail — which is what the breadcrumb's «Board» does
   * (spec-00001-FR-36, spec-00001-AC-37.9).
   */
  function focus(id: string) {
    setDrilled(undefined)
    setDetail(undefined)
    centre(id)
    setPendingFocus({ id, width: canvasWidth })
    void board.select(id)
  }

  /**
   * Selecting on the top-level canvas. The panel it may open takes the right
   * third, so the same wait applies: a click that changes the canvas width ends
   * with the node back in the middle of what is left (issue-00006).
   */
  function select(id: string) {
    setPendingFocus({ id, width: canvasWidth })
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
          {/*
            A count of its own, next to the anomaly count and never folded into
            it: a diagnostic is a reading that drifted, not a broken document,
            so it takes the outline variant and disappears at zero
            (spec-00001-FR-40, AC-40.3/AC-40.5; design-00002 §9).
          */}
          {board.graph.diagnostics.length === 0 ? null : (
            <Badge variant="outline" className="gap-1 text-xs">
              <FileWarning className="size-3" aria-hidden />
              {board.graph.diagnostics.length} diagnostics
            </Badge>
          )}
          <ThemeMenu theme={theme.theme} onChoose={theme.choose} />
        </div>
      </header>

      <ResizablePanelGroup orientation="vertical" {...rows} className="min-h-0 flex-1">
        <ResizablePanel id="work" defaultSize={65} minSize={25}>
          <ResizablePanelGroup
            orientation="horizontal"
            {...(inspector ? inspectorColumns : shown ? detailColumns : columns)}
          >
            <ResizablePanel id="canvas" defaultSize={board.editing || inspector || shown ? 62 : 100} minSize={30}>
              <div className="relative h-full">
                <ReactFlow
                  nodes={sub ? sub.nodes : nodes}
                  edges={sub ? sub.edges : edges}
                  nodeTypes={nodeTypes}
                  // A sub-canvas node is not a document: selecting is the top
                  // level's act, and so is dropping the selection — losing it
                  // here would take the items the sub-canvas is drawn from.
                  // In the sub-canvas a click opens the node's detail instead
                  // (spec-00001-FR-37), and the blank closes it (AC-37.4).
                  onNodeClick={sub ? (_event, node) => setDetail(node.id) : (_event, node) => select(node.id)}
                  onPaneClick={sub ? () => setDetail(undefined) : board.deselect}
                  // Handles exist to anchor edges, not to draw them: every edge
                  // comes from front matter (spec-00001-AC-1.14).
                  nodesConnectable={false}
                  onError={onFlowError}
                  minZoom={minZoom}
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
                        clarifiable={board.clarifiable.includes(selected.type ?? '')}
                        sessionRunning={board.session?.status === 'running'}
                        onPickRelation={focus}
                        onEdit={() => board.setEditing(selected.id)}
                        onStatus={(to) => void board.run(() => api.setStatus(selected.id, to))}
                        onAccept={() => void board.run(() => api.accept(selected.id))}
                        onClarify={() => void board.startSession(() => api.clarify(selected.id))}
                        onAsk={() => void board.startSession(() => api.ask(selected.id))}
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
            ) : shown ? (
              <>
                <ResizableHandle withHandle />
                <ResizablePanel id="detail" defaultSize={38} minSize={20}>
                  <Details target={shown} onGoToRecord={focus} />
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

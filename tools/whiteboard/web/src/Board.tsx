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
import {
  FilePlus,
  FileQuestionMark,
  FileWarning,
  Gauge,
  History,
  Keyboard,
  LayoutDashboard,
  Search,
  Terminal as TerminalIcon,
  TriangleAlert,
} from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { Toaster, toast } from 'sonner'
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
import { type SessionListing, api } from './api.ts'
import { CommandPalette } from './CommandPalette.tsx'
import { CoverageView } from './CoverageView.tsx'
import { CreateDialog } from './CreateDialog.tsx'
import { Details } from './Details.tsx'
import { DiagnosticList, IssueList } from './Drilldowns.tsx'
import { Editor } from './Editor.tsx'
import { Inspector } from './Inspector.tsx'
import { NODE_HEIGHT, NODE_WIDTH } from './layout.ts'
import { NodeCard } from './NodeCard.tsx'
import { SessionHistory } from './SessionHistory.tsx'
import { SessionPanel } from './SessionPanel.tsx'
import { AcceptanceRowNode, CriterionNode, ItemNode } from './SubNodes.tsx'
import { Terminal } from './Terminal.tsx'
import { ThemeMenu } from './ThemeMenu.tsx'
import { Toolbar } from './Toolbar.tsx'
import { evidenceOf, relationsOf, suppressedNodes, toFlowEdges, toFlowNodes } from './canvasModel.ts'
import { onFlowError } from './flowError.ts'
import { JumpContext } from './jump.ts'
import { detailTarget, subCanvas } from './subCanvas.ts'
import { useTheme } from './theme.ts'
import { useBoard } from './useBoard.ts'

type DocNodeData = {
  node: DocNode
  kind?: string
  suppressed?: boolean
  /** The session running on this document, if one is (spec-00003-FR-10). */
  session?: SessionListing
  onShowSession?: (id: string) => void
}

const nodeTypes = {
  doc: ({ data, selected }: { data: DocNodeData; selected?: boolean }) => (
    <NodeCard
      node={data.node}
      kind={data.kind}
      selected={selected ?? false}
      suppressed={data.suppressed}
      session={data.session}
      onShowSession={data.onShowSession}
    />
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
  const [creating, setCreating] = useState(false)
  const [history, setHistory] = useState(false)
  // The session panel (spec-00003-FR-4): opened from the resident top-bar entry,
  // closed by the row that takes the user to a session.
  const [sessionsOpen, setSessionsOpen] = useState(false)
  // The two top-bar counts each open a list of their own; the two never mix
  // (spec-00002-FR-13, FR-14).
  const [issuesOpen, setIssuesOpen] = useState(false)
  const [diagnosticsOpen, setDiagnosticsOpen] = useState(false)
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

  // Which document each running session is on. One per document at most — the
  // concurrency rule is exactly that (spec-00003-FR-2) — so a node has one
  // marker or none, and the same lookup answers whether its entries are locked.
  const runningOn = useMemo(
    () => new Map(board.running.map((session) => [session.sourceId, session])),
    [board.running],
  )

  const nodes = useMemo(() => {
    const laid = toFlowNodes(board.graph, board.placed, board.selected)
    const suppressed = suppressedNodes(board.graph, board.selected)
    return laid.map((node) => {
      const data = node.data as DocNodeData
      return {
        ...node,
        data: {
          ...data,
          kind: board.kinds[data.node.type ?? ''],
          suppressed: suppressed.has(node.id),
          session: runningOn.get(node.id),
          onShowSession: board.showSession,
        },
      }
    })
  }, [board.graph, board.placed, board.selected, board.kinds, runningOn, board.showSession])

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
   * (spec-00001-FR-36, spec-00001-AC-37.9). The target is checked before
   * anything is torn down (close nearest, design-00002 §10): a jump whose
   * document has left the board refuses in place, and the sub-canvas and the
   * detail stay exactly where they were (spec-00001-AC-57.8).
   */
  function focus(id: string) {
    if (!board.graph.nodes.some((node) => node.id === id)) {
      toast.error(`no document ${id} on the board`)
      return
    }
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
    // The inline-id jump's route to the sub-canvas nodes: React Flow renders
    // them from node data, so the context carries what a prop chain cannot
    // (spec-00001-FR-57, design-00002 §9).
    <JumpContext.Provider value={{ idOwners: board.graph.idOwners, onJump: focus }}>
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

        {/*
          The flow's own starting point: an entry document is created here rather
          than by hand outside the board (spec-00001-FR-53). A config that
          declares no entry type has no starting point to offer, so the entry is
          not there at all — an empty dialog would be worse than none
          (spec-00001-AC-53.6).
        */}
        {board.entry.length > 0 ? (
          <Button variant="outline" size="sm" className="gap-2" onClick={() => setCreating(true)}>
            <FilePlus className="size-4" aria-hidden />
            New
          </Button>
        ) : null}

        {/*
          Where the coverage gaps of the whole repo are read off, rather than
          node by node (spec-00002-FR-10). It is a dialog, so it owes nothing to
          the editor, the terminal, a sub-canvas or a running session — it opens
          over any of them (spec-00002-AC-10.5, AC-10.6).
        */}
        <Button variant="outline" size="sm" className="gap-2" onClick={() => board.showCoverage(true)}>
          <Gauge className="size-4" aria-hidden />
          Coverage
        </Button>

        {/* The sessions that have ended are still readable (spec-00001-FR-54). */}
        <Button variant="outline" size="sm" className="gap-2" onClick={() => setHistory(true)}>
          <History className="size-4" aria-hidden />
          History
        </Button>

        <div className="ml-auto flex items-center gap-2">
          {/*
            The way into the session panel, and resident: it is how many sessions
            are running out of how many may be, which is worth reading whether or
            not any are (spec-00003-FR-4). It is also why the stop can never
            become unreachable — a session running with the terminal put away is
            two clicks from being stopped, through here (spec-00001-AC-49.8).
          */}
          <Button
            variant="outline"
            size="sm"
            className="gap-2"
            aria-label="Open the session panel"
            onClick={() => setSessionsOpen(true)}
          >
            <TerminalIcon className="size-4" aria-hidden />
            {board.running.length}/{board.maxSessions}
          </Button>
          {/*
            How many sessions are waiting on an answer (spec-00003-FR-6). The
            icon carries it as much as the number does, so it is not colour
            telling them apart; at zero there is nothing to be told, and the badge
            is not drawn at all — the diagnostics count's zero reading
            (spec-00001-AC-40.5, design-00002 §3).
          */}
          {board.awaitingCount === 0 ? null : (
            <Badge
              variant="secondary"
              className="gap-1 text-xs"
              aria-label={`${board.awaitingCount} awaiting input`}
            >
              <Keyboard className="size-3" aria-hidden />
              {board.awaitingCount}
            </Badge>
          )}
          {/*
            The count is the way into the list (spec-00002-FR-13): above zero
            the badge wraps a real button, so it is reached by Tab and fired by
            Enter. At zero there is nothing to list, and the wording stays what
            it was (spec-00002-AC-13.2).
          */}
          {board.graph.issues.length === 0 ? (
            <span className="text-muted-foreground text-xs">no issues</span>
          ) : (
            <Badge variant="destructive" className="gap-1 text-xs" asChild>
              <button type="button" aria-label="Open the anomaly list" onClick={() => setIssuesOpen(true)}>
                <TriangleAlert className="size-3" aria-hidden />
                {board.graph.issues.length} issues
              </button>
            </Badge>
          )}
          {/*
            A count of its own, next to the anomaly count and never folded into
            it: a diagnostic is a reading that drifted, not a broken document,
            so it takes the outline variant and disappears at zero
            (spec-00001-FR-40, AC-40.3/AC-40.5; design-00002 §9). Zero therefore
            leaves nothing to click, which is all spec-00002-AC-14.2 asks.
          */}
          {board.graph.diagnostics.length === 0 ? null : (
            <Badge variant="outline" className="gap-1 text-xs" asChild>
              <button type="button" aria-label="Open the diagnostics list" onClick={() => setDiagnosticsOpen(true)}>
                <FileWarning className="size-3" aria-hidden />
                {board.graph.diagnostics.length} diagnostics
              </button>
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
                        auditable={board.auditable.includes(selected.type ?? '')}
                        // The two concurrency rules, each read where it holds:
                        // this document's own session, and the cap on all of
                        // them (spec-00003-FR-2, FR-3). Another document's
                        // session locks nothing here (spec-00001-AC-12.8).
                        docBusy={runningOn.has(selected.id)}
                        capReached={board.running.length >= board.maxSessions}
                        agents={board.agents}
                        agent={board.agent}
                        onPickAgent={board.setAgent}
                        onPickRelation={focus}
                        onEdit={() => board.edit(selected.id)}
                        onStatus={(to) => void board.run(() => api.setStatus(selected.id, to))}
                        onAccept={() => void board.run(() => api.accept(selected.id))}
                        onClarify={() => void board.startSession(() => api.clarify(selected.id, board.agent))}
                        onAsk={() => void board.startSession(() => api.ask(selected.id, board.agent))}
                        onAudit={() => void board.startSession(() => api.audit(selected.id, board.agent))}
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
                    draft={board.draft}
                    // A creation ends differently from a revision: the document
                    // is new to the board, so it is taken in and selected
                    // (spec-00001-FR-53).
                    onSaved={
                      board.draft === undefined ? () => void board.refresh() : () => void board.created()
                    }
                    onClose={() => board.edit(undefined)}
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
                    idOwners={board.graph.idOwners}
                    onJump={focus}
                  />
                </ResizablePanel>
              </>
            ) : shown ? (
              <>
                <ResizableHandle withHandle />
                <ResizablePanel id="detail" defaultSize={38} minSize={20}>
                  <Details target={shown} onGoToRecord={focus} idOwners={board.graph.idOwners} onJump={focus} />
                </ResizablePanel>
              </>
            ) : null}
          </ResizablePanelGroup>
        </ResizablePanel>

        {board.terminalOpen ? (
          <>
            <ResizableHandle withHandle />
            <ResizablePanel id="terminal" defaultSize={35} minSize={15}>
              <Terminal
                session={board.shownSession}
                dark={theme.isDark}
                // One terminal per session is kept alive, and the cap on
                // sessions is the cap on them (design-00002 §12).
                keep={board.maxSessions}
                onClose={() => board.setTerminalOpen(false)}
                onStop={() => void board.stopSession()}
              />
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
      <CreateDialog
        types={board.entry}
        open={creating}
        onOpenChange={setCreating}
        onCreate={(type, slug) => void board.create(type, slug)}
      />
      <CoverageView
        open={board.coverageOpen}
        onOpenChange={board.showCoverage}
        rows={board.coverage}
        nodes={board.graph.nodes}
        // Picking an item is a top-level act: the view goes, and `focus` leaves
        // any sub-canvas behind and selects the document — with the inspector
        // following the right-slot rule it already follows (spec-00002-FR-12).
        // A document that has left the disk since the payload was read is
        // refused by `select` with its own toast (spec-00002-AC-12.5).
        onPick={(docId) => {
          board.showCoverage(false)
          focus(docId)
        }}
      />
      {/* Each list closes on its way to the node it names (spec-00002-FR-15);
          `focus` is the same path the palette and the relation list take. */}
      <IssueList
        open={issuesOpen}
        onOpenChange={setIssuesOpen}
        issues={board.graph.issues}
        nodes={board.graph.nodes}
        onPick={(nodeId) => {
          setIssuesOpen(false)
          focus(nodeId)
        }}
      />
      <DiagnosticList
        open={diagnosticsOpen}
        onOpenChange={setDiagnosticsOpen}
        diagnostics={board.graph.diagnostics}
        onPick={(docId) => {
          setDiagnosticsOpen(false)
          focus(docId)
        }}
      />
      {/*
        Picking a session is two acts at once (spec-00003-FR-4): the terminal
        comes up on it, and the board goes to its document. `focus` is the same
        path the palette and the three lists take, and it is what makes the
        second act give way on its own — a session whose document has left the
        board refuses in place with its own toast, and the selection and the
        viewport stay exactly where they were (spec-00003-AC-4.4).
      */}
      <SessionPanel
        open={sessionsOpen}
        onOpenChange={setSessionsOpen}
        sessions={board.sessions}
        showAgent={board.agents.length > 1}
        onPick={(session) => {
          setSessionsOpen(false)
          board.showSession(session.id)
          focus(session.sourceId)
        }}
      />
      <SessionHistory open={history} onOpenChange={setHistory} />
      <Toaster position="bottom-right" theme={theme.isDark ? 'dark' : 'light'} richColors closeButton />
    </div>
    </JumpContext.Provider>
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

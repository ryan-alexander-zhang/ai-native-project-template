import {
  Background,
  Controls,
  type Node as FlowNode,
  MiniMap,
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
  PanelLeft,
  PanelLeftClose,
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
import { AnnotationList } from './AnnotationList.tsx'
import { annotationRows } from './annotationRows.ts'
import { AskEntry } from './AskEntry.tsx'
import { AskList } from './AskList.tsx'
import { CommandPalette } from './CommandPalette.tsx'
import { CoverageView } from './CoverageView.tsx'
import { CreateDialog } from './CreateDialog.tsx'
import { Details } from './Details.tsx'
import { DiagnosticList, IssueList } from './Drilldowns.tsx'
import { Editor, type EditorAnnotate } from './Editor.tsx'
import { Inspector } from './Inspector.tsx'
import { NODE_HEIGHT, NODE_WIDTH } from './layout.ts'
import { NodeCard } from './NodeCard.tsx'
import { NotifySwitch } from './NotifySwitch.tsx'
import { SessionHistory } from './SessionHistory.tsx'
import { SessionPanel } from './SessionPanel.tsx'
import { Sidebar } from './Sidebar.tsx'
import { AcceptanceRowNode, CriterionNode, ItemNode } from './SubNodes.tsx'
import { Terminal } from './Terminal.tsx'
import { ThemeMenu } from './ThemeMenu.tsx'
import { Toolbar } from './Toolbar.tsx'
import { evidenceOf, relationsOf, suppressedNodes, toFlowEdges, toFlowNodes } from './canvasModel.ts'
import { onFlowError } from './flowError.ts'
import { JumpContext } from './jump.ts'
import { readSidebarOpen, writeSidebarOpen } from './sidebar.ts'
import { typeGroups } from './sidebarModel.ts'
import { detailTarget, subCanvas } from './subCanvas.ts'
import { useTheme } from './theme.ts'
import { useBoard } from './useBoard.ts'

type DocNodeData = {
  node: DocNode
  kind?: string
  suppressed?: boolean
  /** The sessions running on this document, of any kind (spec-00005-FR-9). */
  sessions?: SessionListing[]
  onShowSession?: (id: string) => void
}

const nodeTypes = {
  doc: ({ data, selected }: { data: DocNodeData; selected?: boolean }) => (
    <NodeCard
      node={data.node}
      kind={data.kind}
      selected={selected ?? false}
      suppressed={data.suppressed}
      sessions={data.sessions}
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

/**
 * What a minimap block is coloured by: the document's status, or the anomaly
 * colour when its front matter does not parse. A sub-canvas node stands for no
 * document and so has no status to take (design-00002 §17.4); the colours
 * themselves are in index.css, so the theme carries them.
 */
function minimapClass(node: FlowNode): string {
  if (node.type !== 'doc') return ''
  const doc = (node.data as DocNodeData).node
  return doc.ok ? `minimap-status-${doc.status}` : 'minimap-anomaly'
}

function Canvas() {
  // A clicked desktop notification lands exactly where the session panel's row
  // lands (spec-00004-FR-5): `goToSession` below is that one act, held here
  // because half of it is the canvas moving.
  const board = useBoard(goToSession)
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
  // The navigation sidebar's own preference, remembered across opens
  // (spec-00008-FR-5). Put away, it is not rendered at all — the terminal
  // panel's shape, not a zero-width panel (design-00002 §17.1).
  const [sidebarOpen, setSidebarOpen] = useState(readSidebarOpen)
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
  // The sidebar's width is its own, so opening a right-slot panel and moving
  // the sidebar's edge never write over each other (design-00002 §9, §17.1).
  const sidebarColumns = useDefaultLayout({
    id: 'whiteboard-sidebar-columns',
    panelIds: ['sidebar', 'board'],
  })

  /**
   * Which running sessions each document has. A **list**, not one apiece: an ask
   * holds no document, so one document can carry a terminal-form session and any
   * number of asks at once (spec-00005-FR-6). The same lookup answers what the
   * node's one marker shows and whether the entries are locked — and the two
   * read it differently: the marker counts every kind (spec-00005-FR-9), the
   * lock only the kinds that hold the document (design-00002 §14).
   */
  const runningOn = useMemo(() => {
    const on = new Map<string, SessionListing[]>()
    for (const session of board.running) {
      const held = on.get(session.sourceId)
      if (held === undefined) on.set(session.sourceId, [session])
      else held.push(session)
    }
    return on
  }, [board.running])
  const docBusy = (id: string) => (runningOn.get(id) ?? []).some((session) => session.kind !== 'ask')
  /**
   * The cowrite session holding a document, if one is (spec-00006-FR-4, FR-10):
   * the same lookup answers what the status lock disables and what the editor
   * does — and the two read it differently, the lock only needing that there is
   * one, the editor also needing whether it is waiting on the user.
   */
  const cowriteOn = (id: string) => (runningOn.get(id) ?? []).find((session) => session.kind === 'cowrite')
  const knownDoc = (id: string) => board.graph.nodes.some((node) => node.id === id)

  /**
   * The editor's own way to ask, of the same shape as the node's
   * (design-00002 §14). An anomalous document has none — its editor is where it
   * is repaired, not where it is questioned (spec-00005-AC-7.3) — and no
   * document has one while no agent declares a headless form (AC-7.4). A buffer
   * that is not a document yet is not on the graph, so it has none either.
   */
  const editingNode = board.graph.nodes.find((node) => node.id === board.editing)
  // The cowrite session on the document in the editor, which is what puts the
  // buffer under the reload rule and the lock (spec-00006-FR-4).
  const editorCowrite = board.editing === undefined ? undefined : cowriteOn(board.editing)
  const askEntry =
    board.askAgents.length > 0 && editingNode?.ok === true ? (
      // Keyed by the document, so a draft written about one is never submitted
      // against the next one opened here; the cap locks it exactly as it locks
      // the node's own entry (spec-00003-FR-3).
      <AskEntry
        key={editingNode.id}
        agents={board.askAgents}
        disabled={board.running.length >= board.maxSessions}
        onSubmit={(question, agent) => board.ask({ docId: editingNode.id, question, agent })}
      />
    ) : undefined

  /**
   * The annotation list as one reading of the three payloads that own its parts
   * (design-00002 §16.4), and the two things drawn from it: the traces of the
   * unsubmitted, locatable ones (spec-00007-AC-9.13) and the located one.
   */
  const annotationRowList = useMemo(
    () => (board.annotations === undefined ? [] : annotationRows(board.annotations, board.threads)),
    [board.annotations, board.threads],
  )
  const traces = useMemo(
    () =>
      annotationRowList.flatMap((row) =>
        row.state === 'pending' && row.range !== undefined ? [{ id: row.id, ...row.range }] : [],
      ),
    [annotationRowList],
  )
  const locatedRow = annotationRowList.find((row) => row.id === board.locatedAnnotation)
  const reanchorRow = annotationRowList.find((row) => row.id === board.reanchoring)
  const annotationView = board.annotations
  /**
   * The document every annotation entry is addressed to. There is one whenever
   * there is a payload — the annotations are read for the document the editor is
   * on and carried with its id (design-00002 §16.8) — and naming it here binds
   * each entry to that document rather than to whichever one the editor may have
   * moved on to by the time the request goes.
   */
  const annotated = board.editing!
  const annotate: EditorAnnotate | undefined =
    annotationView === undefined
      ? undefined
      : {
          docId: annotated,
          // The two gates come from the submit statement and from nowhere else:
          // the board rules on neither (design-00002 §16.2).
          eligible: {
            question: annotationView.submitPreview.questionEligible,
            issue: annotationView.submitPreview.issueEligible,
          },
          traces,
          ...(locatedRow?.range === undefined
            ? {}
            : { locate: { id: locatedRow.id, range: locatedRow.range, askedAt: board.locatedAt } }),
          ...(reanchorRow === undefined ? {} : { reanchor: { id: reanchorRow.id, text: reanchorRow.text } }),
          onAdd: (input) => board.addAnnotation(annotated, input),
          onReanchor: (input) => board.finishReanchor(annotated, input.anchor),
          onLeaveLocate: () => board.locateAnnotation(annotated, undefined),
          onUnsaved: board.setUnsavedBuffer,
        }

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
          sessions: runningOn.get(node.id),
          onShowSession: board.showSession,
        },
      }
    })
  }, [board.graph, board.placed, board.selected, board.kinds, runningOn, board.showSession])

  // The sidebar's list of every document on the board, grouped and ordered by
  // the canvas's own rule (spec-00008-FR-1). `kinds` is the flow config's type
  // map, so its key order is the declared column order.
  const groups = useMemo(() => typeGroups(board.graph, Object.keys(board.kinds)), [board.graph, board.kinds])

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

  // Esc leaves the re-anchor mode: it is a gesture under way, and the way out of
  // one is the key that leaves everything else here (design-00002 §16.4).
  useEffect(() => {
    if (board.reanchoring === undefined) return
    const leave = (event: KeyboardEvent) => {
      if (event.key === 'Escape') board.startReanchor(annotated, undefined)
    }
    window.addEventListener('keydown', leave)
    return () => window.removeEventListener('keydown', leave)
  }, [board.reanchoring, board.startReanchor, annotated])

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
   * Go to a session: the terminal comes up on it, and the board goes to its
   * document (spec-00003-FR-4). One act with two callers — the session panel's
   * row and a clicked desktop notification (spec-00004-FR-5). `focus` is the
   * same path the palette and the three lists take, and it is what makes the
   * second half give way on its own: a session whose document has left the
   * board refuses in place with its own toast, the terminal shows it all the
   * same, and the selection and the viewport stay exactly where they were
   * (spec-00003-AC-4.4, spec-00004-AC-5.3).
   *
   * An ask goes somewhere else entirely — its document's ask list — and says so
   * itself when it cannot, which is why the canvas half waits on its word
   * (spec-00005-FR-9).
   */
  function goToSession(session: SessionListing) {
    if (board.showSession(session.id)) focus(session.sourceId)
  }

  /** Put the sidebar away or bring it back, and remember which (spec-00008-FR-5). */
  function toggleSidebar() {
    writeSidebarOpen(!sidebarOpen)
    setSidebarOpen(!sidebarOpen)
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
        {/* The way the list of documents is put away and brought back, and the
            leftmost thing in the bar because that is the side it is on
            (design-00002 §17.1). */}
        <Button
          variant="ghost"
          size="icon-sm"
          aria-label={sidebarOpen ? 'Hide navigation' : 'Show navigation'}
          onClick={toggleSidebar}
        >
          {sidebarOpen ? <PanelLeftClose aria-hidden /> : <PanelLeft aria-hidden />}
        </Button>
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
          {/* Being called back when the board is not in front of the user is a
              choice made once and remembered, so its switch is resident next to
              the theme (spec-00004-FR-1, design-00002 §3). */}
          <NotifySwitch state={board.notifyState} onToggle={board.toggleNotify} />
          <ThemeMenu theme={theme.theme} onChoose={theme.choose} />
        </div>
      </header>

      <ResizablePanelGroup orientation="vertical" {...rows} className="min-h-0 flex-1">
        <ResizablePanel id="work" defaultSize={65} minSize={25}>
          {/* The sidebar takes a slot of the work row of its own, outside the
              right slot's three layouts: the widths are separate memories and
              neither reads the other's (design-00002 §17.1). */}
          <ResizablePanelGroup orientation="horizontal" {...sidebarColumns}>
            {sidebarOpen ? (
              <>
                <ResizablePanel id="sidebar" defaultSize={18} minSize={12}>
                  <Sidebar groups={groups} selected={board.selected} onPick={focus} />
                </ResizablePanel>
                <ResizableHandle withHandle />
              </>
            ) : null}

            <ResizablePanel id="board" defaultSize={82}>
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
                      {/* Where in the whole graph the viewport is (spec-00008-FR-7).
                          One React Flow instance holds both datasets, so the
                          sub-canvas gets its own minimap for free. */}
                      <MiniMap pannable zoomable nodeClassName={minimapClass} />
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
                            // this document's own terminal-form session, and the cap
                            // on all of them (spec-00003-FR-2, FR-3). Another
                            // document's session locks nothing here
                            // (spec-00001-AC-12.8), and neither does an ask on this
                            // one (spec-00005-FR-6).
                            docBusy={docBusy(selected.id)}
                            capReached={board.running.length >= board.maxSessions}
                            agents={board.agents}
                            askAgents={board.askAgents}
                            agent={board.agent}
                            onPickAgent={board.setAgent}
                            onPickRelation={focus}
                            onEdit={() => board.edit(selected.id)}
                            onStatus={(to) => void board.run(() => api.setStatus(selected.id, to))}
                            onAccept={() => void board.run(() => api.accept(selected.id))}
                            onClarify={() => void board.startSession(() => api.clarify(selected.id, board.agent))}
                            onAsk={(question, agent) => board.ask({ docId: selected.id, question, agent })}
                            // The status lock's one reading, and the entry's own
                            // materials check against the board (spec-00006-FR-10, FR-3).
                            cowriting={cowriteOn(selected.id) !== undefined}
                            knownDoc={knownDoc}
                            onCowrite={(materials, agent) =>
                              board.cowrite({ docId: selected.id, materials, agent })
                            }
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
                        mode={board.editorMode}
                        onMode={(mode) => board.showEditorMode(board.editing!, mode)}
                        ask={askEntry}
                        asks={
                          <AskList
                            threads={board.threads}
                            located={board.located}
                            onLocate={board.locate}
                            onFollowUp={(threadId, question) =>
                              board.ask({ docId: board.editing!, question, threadId })
                            }
                            onResend={(threadId, question) =>
                              void board.ask({ docId: board.editing!, question, threadId, resend: true })
                            }
                          />
                        }
                        annotate={annotate}
                        annotations={
                          annotationView === undefined ? undefined : (
                            <AnnotationList
                              rows={annotationRowList}
                              preview={annotationView.submitPreview}
                              // A row is locatable when its anchor lands somewhere:
                              // the payload's own fresh reading, and no second
                              // judgment of it here (design-00002 §16.4).
                              locatable={(row) => row.range !== undefined}
                              located={board.locatedAnnotation}
                              reanchoring={board.reanchoring}
                              unsaved={board.unsavedBuffer}
                              submitting={board.submitting}
                              agents={board.agents}
                              askAgents={board.askAgents}
                              onLocate={(row) => board.locateAnnotation(annotated, row.id)}
                              // The question path's navigation reuses
                              // spec-00005-FR-9 whole, whatever state the thread is
                              // in; a thread that has left the payload is the
                              // close-nearest case — a toast, and the view stays
                              // (design-00002 §16.6).
                              onThread={(row) => {
                                if (!board.threads.some((one) => one.id === row.threadId)) {
                                  toast.error(`no thread ${row.threadId} on this document`)
                                  return
                                }
                                board.showEditorMode(annotated, 'asks')
                                board.locate(row.threadId)
                              }}
                              onSession={(row) => row.sessionId !== undefined && board.showSession(row.sessionId)}
                              onChange={(id, change) => board.changeAnnotation(annotated, id, change)}
                              onRemove={(id) => board.removeAnnotation(annotated, id)}
                              onReanchor={(id) => board.startReanchor(annotated, id)}
                              onSubmit={(agents) => void board.submitAnnotations(annotated, agents)}
                            />
                          )
                        }
                        // The workspace half of a cowrite (spec-00006-FR-4): the
                        // target's text on disk, re-read with each refresh, and the
                        // lock while the agent has the pen — a running session that
                        // is not waiting on the user.
                        disk={board.disk}
                        readOnly={editorCowrite !== undefined && editorCowrite.awaiting !== true}
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
      {/* The blank mode is what it always was; the cowrite mode files the
          document and starts the session in one call (spec-00006-FR-2). */}
      <CreateDialog
        types={board.entry}
        agents={board.agents}
        knownDoc={knownDoc}
        open={creating}
        onOpenChange={setCreating}
        onCreate={(type, slug) => void board.create(type, slug)}
        onCowrite={(type, slug, materials, agent) => board.cowrite({ create: { type, slug }, materials, agent })}
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
      {/* Picking a session closes the panel and goes to it — `goToSession` is the
          whole act, and the same one a desktop notification's click performs
          (spec-00003-FR-4, spec-00004-FR-5). */}
      <SessionPanel
        open={sessionsOpen}
        onOpenChange={setSessionsOpen}
        sessions={board.sessions}
        showAgent={board.agents.length > 1}
        onPick={(session) => {
          setSessionsOpen(false)
          goToSession(session)
        }}
        // The stop stays where the panel is: ending a session is not going to
        // it, so the list is not closed on the way (spec-00005-FR-7).
        onStop={(session) => void board.stopSession(session.id)}
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

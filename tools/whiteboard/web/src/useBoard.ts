import { useCallback, useEffect, useRef, useState } from 'react'
import { toast } from 'sonner'
import type { DocKind, FlowStep } from '../../src/config.ts'
import type { DocGraph } from '../../src/docRepository.ts'
import { type ItemsView, declaresItems } from '../../src/requirements.ts'
import { ApiError, type CoverageRow, type SessionInfo, api } from './api.ts'
import { connectEvents } from './eventSocket.ts'
import { prefillFrontMatter } from './frontMatter.ts'
import { type Placed, layoutGraph } from './layout.ts'

const EMPTY_GRAPH: DocGraph = { nodes: [], edges: [], issues: [], diagnostics: [] }

/**
 * How many gap ids a refusal toast names. A plan can deliver dozens of items,
 * and a toast listing every one of them is a wall the count cannot be read off
 * — so the list is cut and the count, which is the number the user acts on,
 * is kept whole (design-00002 §3).
 */
const GAPS_NAMED = 5

/**
 * What a refusal reads as. The `resolved` gate names its gaps one by one
 * (spec-00001-FR-52) and the toast is that list's presentation: the count
 * first, then as many ids as fit (design-00002 §3). Every other refusal is its
 * own message and is passed through untouched.
 */
function refusalText(error: unknown): string {
  if (error instanceof ApiError && error.gaps !== undefined) {
    const named = error.gaps.slice(0, GAPS_NAMED)
    const rest = error.gaps.length - named.length
    const tail = rest === 0 ? '' : `, and ${rest} more`
    return `${error.gaps.length} items unverified: ${named.join(', ')}${tail}`
  }
  return error instanceof Error ? error.message : String(error)
}

/** Board state: what is on the canvas, what is selected, and which panels are open. */
export function useBoard() {
  const [graph, setGraph] = useState<DocGraph>(EMPTY_GRAPH)
  const [placed, setPlaced] = useState<Placed[]>([])
  const [kinds, setKinds] = useState<Record<string, DocKind>>({})
  // Relation field order drives the relation list's grouping (spec-00001-FR-30).
  const [relationOrder, setRelationOrder] = useState<string[]>([])
  // Which types may be clarified and which audited are the payload's answer,
  // never the board's: the entries follow the sets `GET /api/config` sends, so a
  // set that changes there changes what is on show here and the two cannot drift
  // (spec-00001-FR-56, AC-56.2).
  const [clarifiable, setClarifiable] = useState<string[]>([])
  const [auditable, setAuditable] = useState<string[]>([])
  // The types a document may be created at (spec-00001-FR-53); none means no
  // create entry at all (spec-00001-AC-53.6).
  const [entry, setEntry] = useState<string[]>([])
  // The agents a session may run under, and the one the next session will use.
  // A single agent is left unnamed: the request then carries no agent field and
  // the server takes the first, as it always did (spec-00001-AC-55.4).
  const [agents, setAgents] = useState<string[]>([])
  const [agent, setAgent] = useState<string>()
  const [selected, setSelected] = useState<string>()
  // Carried with the document it was read for, so a panel never shows the
  // previous selection's items while the next ones are in flight.
  const [items, setItems] = useState<{ docId: string; view: ItemsView }>()
  const [transitions, setTransitions] = useState<string[]>([])
  const [nextSteps, setNextSteps] = useState<FlowStep[]>([])
  // Editor and terminal are independent: an agent can work while a document is open.
  const [editing, setEditing] = useState<string>()
  // The prefilled buffer of a document that is not on disk yet: set together
  // with `editing`, and what tells the editor that saving creates rather than
  // revises (spec-00001-FR-53).
  const [draft, setDraft] = useState<string>()
  const [terminalOpen, setTerminalOpen] = useState(false)
  const [session, setSession] = useState<SessionInfo | null>(null)
  // The global coverage view (spec-00002-FR-10): whether it is on show, and the
  // payload it is showing. Undefined is «not read yet», which is what the first
  // moment after opening looks like.
  const [coverageOpen, setCoverageOpen] = useState(false)
  const [coverage, setCoverage] = useState<CoverageRow[]>()

  // Column order comes from the config; the layout is meaningless without it.
  const typeOrder = useRef<string[]>([])
  // The same flag as `coverageOpen`, readable from `refresh` without making the
  // callback depend on it: a `refresh` rebuilt on every open would tear the
  // docs-change channel down and dial it again (design-00002 §10).
  const viewing = useRef(false)

  /** The coverage payload, re-read (spec-00002-AC-10.4). A failure is the toast every read gets. */
  const readCoverage = useCallback(async () => {
    try {
      setCoverage(await api.coverage())
    } catch (error) {
      toast.error(error instanceof Error ? error.message : String(error))
    }
  }, [])

  /**
   * Open or close the coverage view. Closing lets the payload go: the view is
   * re-read from scratch when it comes back, so nothing stale is ever on show.
   */
  const showCoverage = useCallback(
    (open: boolean) => {
      viewing.current = open
      setCoverageOpen(open)
      if (open) void readCoverage()
      else setCoverage(undefined)
    },
    [readCoverage],
  )

  /**
   * The one way the board takes the docs in again (spec-00001-FR-44): all three
   * triggers — a push, an action of the board's own, the end of a session — come
   * through here, so what is kept and what is let go of cannot differ between
   * them (design-00002 §10). The items of the document on show follow the graph
   * through the effect below.
   *
   * The session state is re-read with the graph, not just at load: a session that
   * ended is exactly what a refresh may have been sent to tell us about, and the
   * badge, the three entries and the stop all hang off it (issue-00013).
   */
  const refresh = useCallback(async () => {
    const [next, { current }] = await Promise.all([api.graph(), api.session()])
    setGraph(next)
    setPlaced(layoutGraph(next, typeOrder.current))
    setSession(current)
    // The selection is held by id, never by position: a document still on the
    // board keeps it, and one that has left the disk takes it with it, closing
    // its toolbar (spec-00001-AC-44.6).
    setSelected((current) =>
      current !== undefined && next.nodes.some((node) => node.id === current) ? current : undefined,
    )
    // The third payload of the one refresh path, and only while somebody is
    // looking at it: the coverage view has no refresh of its own, and the read
    // is the heaviest the board makes (design-00001 §6, spec-00002-AC-10.4).
    if (viewing.current) await readCoverage()
    return next
  }, [readCoverage])

  /** Hold a document as the selection and read what its toolbar offers. */
  const load = useCallback(async (id: string) => {
    setSelected(id)
    const [nextTransitions, steps] = await Promise.all([api.transitions(id), api.nextSteps(id)])
    setTransitions(nextTransitions)
    setNextSteps(steps)
  }, [])

  const select = useCallback(
    async (id: string) => {
      // Selecting is only meaningful for a document that is on the board. The
      // relation list can offer a broken link's target, which is not
      // (issue-00005) — refuse here, where the invariant belongs, rather than
      // at each call site.
      if (!graph.nodes.some((node) => node.id === id)) {
        toast.error(`no document ${id} on the board`)
        return
      }
      await load(id)
    },
    [graph, load],
  )

  const deselect = useCallback(() => setSelected(undefined), [])

  /** Run a board action, refresh the graph, and surface a refusal as a toast. */
  const run = useCallback(
    async (action: () => Promise<unknown>) => {
      try {
        await action()
        await refresh()
      } catch (error) {
        toast.error(refusalText(error))
      }
    },
    [refresh],
  )

  /**
   * The one way a session opens, whichever kind it is (spec-00001-FR-18): the
   * started session takes the single slot and the terminal comes up with it. A
   * refusal — the slot is taken, the document is gone — leaves both alone.
   */
  const startSession = useCallback(
    async (start: () => Promise<SessionInfo>) => {
      await run(async () => {
        setSession(await start())
        setTerminalOpen(true)
      })
    },
    [run],
  )

  /**
   * The one way a session ends on the user's word (spec-00001-FR-49). The board
   * takes the finished session back from the server rather than assuming it: the
   * three entries come back with it, and the graph is re-read like any action's.
   */
  const stopSession = useCallback(async () => {
    await run(async () => {
      setSession(await api.stopSession())
    })
  }, [run])

  const advance = useCallback(
    async (sourceId: string, targetType: string) => {
      await startSession(() => api.advance(sourceId, targetType, agent))
    },
    [startSession, agent],
  )

  /**
   * Open a document's own text, or close the panel. Either way the prefilled
   * buffer goes: a creation abandoned must not follow the next document into the
   * editor.
   */
  const edit = useCallback((id?: string) => {
    setDraft(undefined)
    setEditing(id)
  }, [])

  /**
   * Open a new document's buffer (spec-00001-FR-53). The server allocates the
   * number and hands back the type's template; the id is that prefix and the
   * slug the user chose. Nothing is written — the buffer is created on save, so
   * a dialog thought better of leaves no file behind.
   */
  const create = useCallback(async (type: string, slug: string) => {
    try {
      const { idPrefix, template } = await api.createPrefill(type)
      const id = `${idPrefix}${slug}`
      setDraft(prefillFrontMatter(template, id, type))
      setEditing(id)
    } catch (error) {
      toast.error(refusalText(error))
    }
  }, [])

  /**
   * A created document exists from here on, so the prefilled buffer is done with
   * and the board takes the new node in and selects it: what was just created is
   * what the user wants in front of them (spec-00001-FR-53).
   */
  const created = useCallback(async () => {
    const id = editing
    setDraft(undefined)
    setEditing(undefined)
    const next = await refresh()
    if (id !== undefined && next.nodes.some((node) => node.id === id)) await load(id)
  }, [editing, refresh, load])

  // Only a spec or a rule declares requirement items, and only for those does
  // the inspector panel exist at all (spec-00001-FR-31). A document whose front
  // matter is broken still gets read: its body is what the panel is for.
  useEffect(() => {
    const node = graph.nodes.find((candidate) => candidate.id === selected)
    if (!node || !declaresItems(node.type)) {
      setItems(undefined)
      return
    }
    let live = true
    void api
      .items(node.id)
      .then((view) => {
        if (live) setItems({ docId: node.id, view })
      })
      .catch((error) => {
        if (!live) return
        setItems(undefined)
        toast.error(error instanceof Error ? error.message : String(error))
      })
    return () => {
      live = false
    }
  }, [graph, selected])

  // A session outlives the browser, so a board opening fresh reattaches to it.
  useEffect(() => {
    // Config first: laying out before the column order lands would place every
    // node in the unknown-type bucket and then move it (spec-00001-AC-1.12).
    void (async () => {
      try {
        const config = await api.config()
        typeOrder.current = Object.keys(config.types)
        setKinds(config.types)
        setRelationOrder(config.relations)
        setClarifiable(config.clarifiable)
        setAuditable(config.auditable)
        setEntry(config.entry)
        const names = config.agents.map((declared) => declared.name)
        setAgents(names)
        // One agent is no choice at all: it is left unnamed so the request
        // carries no agent field (spec-00001-AC-55.4). More than one, and the
        // first is the one on show until the user picks another.
        setAgent(names.length > 1 ? names[0] : undefined)
      } catch (error) {
        // A board with no column order still beats no board: the graph is the
        // thing the user came for, so draw it and say why it looks odd.
        toast.error(error instanceof Error ? error.message : String(error))
      }
      await refresh()
    })()
    void api.session().then(({ current }) => {
      setSession(current)
      if (current?.status === 'running') setTerminalOpen(true)
    })
  }, [refresh])

  // docs/ moves under the board more often than the board moves it — an agent
  // or an editor elsewhere — so the change is pushed and the board re-reads
  // (spec-00001-FR-42). No channel means no push, which costs the board nothing
  // else (spec-00001-FR-43).
  useEffect(() => {
    const link = connectEvents(() => void refresh())
    return () => link.close()
  }, [refresh])

  return {
    graph,
    placed,
    kinds,
    relationOrder,
    clarifiable,
    auditable,
    entry,
    agents,
    agent,
    selected,
    selectedNode: graph.nodes.find((node) => node.id === selected),
    items: items !== undefined && items.docId === selected ? items.view : undefined,
    transitions,
    nextSteps,
    editing,
    draft,
    terminalOpen,
    session,
    coverageOpen,
    coverage,
    showCoverage,
    edit,
    setTerminalOpen,
    setAgent,
    refresh,
    select,
    deselect,
    run,
    startSession,
    stopSession,
    advance,
    create,
    created,
  }
}

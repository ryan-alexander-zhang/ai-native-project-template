import { useCallback, useEffect, useRef, useState } from 'react'
import { toast } from 'sonner'
import type { DocKind, FlowStep } from '../../src/config.ts'
import type { DocGraph } from '../../src/docRepository.ts'
import { type ItemsView, declaresItems } from '../../src/requirements.ts'
import { ApiError, type SessionInfo, api } from './api.ts'
import { connectEvents } from './eventSocket.ts'
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
  // Which types may be clarified is the config's answer, never the board's: the
  // types carrying a focus line are exactly the clarifiable ones on show
  // (spec-00001-FR-48).
  const [clarifiable, setClarifiable] = useState<string[]>([])
  const [selected, setSelected] = useState<string>()
  // Carried with the document it was read for, so a panel never shows the
  // previous selection's items while the next ones are in flight.
  const [items, setItems] = useState<{ docId: string; view: ItemsView }>()
  const [transitions, setTransitions] = useState<string[]>([])
  const [nextSteps, setNextSteps] = useState<FlowStep[]>([])
  // Editor and terminal are independent: an agent can work while a document is open.
  const [editing, setEditing] = useState<string>()
  const [terminalOpen, setTerminalOpen] = useState(false)
  const [session, setSession] = useState<SessionInfo | null>(null)

  // Column order comes from the config; the layout is meaningless without it.
  const typeOrder = useRef<string[]>([])

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
    return next
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
      setSelected(id)
      const [nextTransitions, steps] = await Promise.all([api.transitions(id), api.nextSteps(id)])
      setTransitions(nextTransitions)
      setNextSteps(steps)
    },
    [graph],
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
      await startSession(() => api.advance(sourceId, targetType))
    },
    [startSession],
  )

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
        setClarifiable(Object.keys(config.focus))
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
    selected,
    selectedNode: graph.nodes.find((node) => node.id === selected),
    items: items !== undefined && items.docId === selected ? items.view : undefined,
    transitions,
    nextSteps,
    editing,
    terminalOpen,
    session,
    setEditing,
    setTerminalOpen,
    refresh,
    select,
    deselect,
    run,
    startSession,
    stopSession,
    advance,
  }
}

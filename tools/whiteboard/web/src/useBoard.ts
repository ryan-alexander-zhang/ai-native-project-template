import { useCallback, useEffect, useRef, useState } from 'react'
import { toast } from 'sonner'
import type { DocKind, FlowStep } from '../../src/config.ts'
import type { DocGraph } from '../../src/docRepository.ts'
import { type SessionInfo, api } from './api.ts'
import { type Placed, layoutGraph } from './layout.ts'

const EMPTY_GRAPH: DocGraph = { nodes: [], edges: [], issues: [] }

/** Board state: what is on the canvas, what is selected, and which panels are open. */
export function useBoard() {
  const [graph, setGraph] = useState<DocGraph>(EMPTY_GRAPH)
  const [placed, setPlaced] = useState<Placed[]>([])
  const [kinds, setKinds] = useState<Record<string, DocKind>>({})
  // Relation field order drives the relation list's grouping (spec-00001-FR-30).
  const [relationOrder, setRelationOrder] = useState<string[]>([])
  const [selected, setSelected] = useState<string>()
  const [transitions, setTransitions] = useState<string[]>([])
  const [nextSteps, setNextSteps] = useState<FlowStep[]>([])
  // Editor and terminal are independent: an agent can work while a document is open.
  const [editing, setEditing] = useState<string>()
  const [terminalOpen, setTerminalOpen] = useState(false)
  const [session, setSession] = useState<SessionInfo | null>(null)

  // Column order comes from the config; the layout is meaningless without it.
  const typeOrder = useRef<string[]>([])

  const refresh = useCallback(async () => {
    const next = await api.graph()
    setGraph(next)
    setPlaced(layoutGraph(next, typeOrder.current))
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
        toast.error(error instanceof Error ? error.message : String(error))
      }
    },
    [refresh],
  )

  const advance = useCallback(
    async (sourceId: string, targetType: string) => {
      await run(async () => {
        setSession(await api.advance(sourceId, targetType))
        setTerminalOpen(true)
      })
    },
    [run],
  )

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

  return {
    graph,
    placed,
    kinds,
    relationOrder,
    selected,
    selectedNode: graph.nodes.find((node) => node.id === selected),
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
    advance,
  }
}

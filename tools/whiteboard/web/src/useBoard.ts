import { useCallback, useEffect, useState } from 'react'
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
  const [selected, setSelected] = useState<string>()
  const [transitions, setTransitions] = useState<string[]>([])
  const [nextSteps, setNextSteps] = useState<FlowStep[]>([])
  // Editor and terminal are independent: an agent can work while a document is open.
  const [editing, setEditing] = useState<string>()
  const [terminalOpen, setTerminalOpen] = useState(false)
  const [session, setSession] = useState<SessionInfo | null>(null)

  const refresh = useCallback(async () => {
    const next = await api.graph()
    setGraph(next)
    setPlaced(await layoutGraph(next))
    return next
  }, [])

  const select = useCallback(async (id: string) => {
    setSelected(id)
    const [nextTransitions, steps] = await Promise.all([api.transitions(id), api.nextSteps(id)])
    setTransitions(nextTransitions)
    setNextSteps(steps)
  }, [])

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
    void refresh()
    void api.config().then((config) => setKinds(config.types))
    void api.session().then(({ current }) => {
      setSession(current)
      if (current?.status === 'running') setTerminalOpen(true)
    })
  }, [refresh])

  return {
    graph,
    placed,
    kinds,
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

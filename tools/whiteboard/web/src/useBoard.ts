import { useCallback, useEffect, useState } from 'react'
import type { FlowStep } from '../../src/config.ts'
import type { DocGraph } from '../../src/docRepository.ts'
import { api } from './api.ts'
import { type Placed, layoutGraph } from './layout.ts'

export type Panel = { kind: 'none' } | { kind: 'editor'; docId: string } | { kind: 'terminal' }

const EMPTY_GRAPH: DocGraph = { nodes: [], edges: [], issues: [] }

/** Board state: what is on the canvas, what is selected, and which panel is open. */
export function useBoard() {
  const [graph, setGraph] = useState<DocGraph>(EMPTY_GRAPH)
  const [placed, setPlaced] = useState<Placed[]>([])
  const [selected, setSelected] = useState<string>()
  const [transitions, setTransitions] = useState<string[]>([])
  const [nextSteps, setNextSteps] = useState<FlowStep[]>([])
  const [panel, setPanel] = useState<Panel>({ kind: 'none' })
  const [message, setMessage] = useState('')

  const refresh = useCallback(async () => {
    const next = await api.graph()
    setGraph(next)
    setPlaced(await layoutGraph(next))
    return next
  }, [])

  const select = useCallback(async (id: string) => {
    setSelected(id)
    setMessage('')
    const [nextTransitions, steps] = await Promise.all([api.transitions(id), api.nextSteps(id)])
    setTransitions(nextTransitions)
    setNextSteps(steps)
  }, [])

  const deselect = useCallback(() => {
    setSelected(undefined)
    setPanel({ kind: 'none' })
  }, [])

  /** Run a board action, refresh the graph, and surface a refusal instead of throwing. */
  const run = useCallback(
    async (action: () => Promise<unknown>) => {
      try {
        await action()
        setMessage('')
        await refresh()
      } catch (error) {
        setMessage(error instanceof Error ? error.message : String(error))
      }
    },
    [refresh],
  )

  const advance = useCallback(
    async (sourceId: string, targetType: string) => {
      await run(async () => {
        await api.advance(sourceId, targetType)
        setPanel({ kind: 'terminal' })
      })
    },
    [run],
  )

  // A session outlives the browser, so a board opening fresh reattaches to it.
  useEffect(() => {
    void refresh()
    void api.session().then(({ current }) => {
      if (current?.status === 'running') setPanel({ kind: 'terminal' })
    })
  }, [refresh])

  return {
    graph,
    placed,
    selected,
    selectedNode: graph.nodes.find((node) => node.id === selected),
    transitions,
    nextSteps,
    panel,
    message,
    setPanel,
    refresh,
    select,
    deselect,
    run,
    advance,
  }
}

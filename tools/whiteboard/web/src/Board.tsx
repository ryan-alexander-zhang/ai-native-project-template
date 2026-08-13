import { Background, Controls, ReactFlow, ReactFlowProvider, useReactFlow } from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { useMemo, useState } from 'react'
import type { DocNode } from '../../src/docRepository.ts'
import { api } from './api.ts'
import { Editor } from './Editor.tsx'
import { NODE_HEIGHT, NODE_WIDTH } from './layout.ts'
import { NodeCard } from './NodeCard.tsx'
import { Terminal } from './Terminal.tsx'
import { Toolbar } from './Toolbar.tsx'
import { findMatch, toFlowEdges, toFlowNodes } from './canvasModel.ts'
import { useBoard } from './useBoard.ts'

const nodeTypes = {
  doc: ({ data, selected }: { data: { node: DocNode }; selected?: boolean }) => (
    <NodeCard node={data.node} selected={selected ?? false} />
  ),
}

function Canvas() {
  const board = useBoard()
  const { setCenter } = useReactFlow()
  const [query, setQuery] = useState('')

  const nodes = useMemo(
    () => toFlowNodes(board.graph, board.placed, board.selected),
    [board.graph, board.placed, board.selected],
  )
  const edges = useMemo(() => toFlowEdges(board.graph), [board.graph])
  const selected = board.selectedNode

  /** Centre the viewport on a node and select it — the "focus" of spec-00001 §7. */
  function focus(id: string) {
    const at = board.placed.find((position) => position.id === id)
    if (!at) return
    setCenter(at.x + NODE_WIDTH / 2, at.y + NODE_HEIGHT / 2, { zoom: 1, duration: 300 })
    void board.select(id)
  }

  return (
    <div className="board">
      <header className="board__head">
        <strong>docs whiteboard</strong>
        <input
          aria-label="Find a document"
          placeholder="find a document"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          onKeyDown={(event) => {
            const hit = findMatch(board.graph.nodes, query)
            if (event.key === 'Enter' && hit) focus(hit.id)
          }}
        />
        <span className="board__issues">
          {board.graph.issues.length === 0 ? 'no issues' : `${board.graph.issues.length} issues`}
        </span>
      </header>

      <div className="board__canvas">
        <ReactFlow
          nodes={nodes}
          edges={edges}
          nodeTypes={nodeTypes}
          onNodeClick={(_event, node) => void board.select(node.id)}
          onPaneClick={board.deselect}
          fitView
        >
          <Background />
          <Controls />
        </ReactFlow>

        {selected ? (
          <Toolbar
            node={selected}
            transitions={board.transitions}
            nextSteps={board.nextSteps}
            onEdit={() => board.setPanel({ kind: 'editor', docId: selected.id })}
            onStatus={(to) => void board.run(() => api.setStatus(selected.id, to))}
            onAccept={() => void board.run(() => api.accept(selected.id))}
            onClarify={(questions) => void board.run(() => api.clarify(selected.id, questions))}
            onAdvance={(targetType) => void board.advance(selected.id, targetType)}
          />
        ) : null}
      </div>

      {board.message ? <p className="board__message">{board.message}</p> : null}

      {board.panel.kind === 'editor' ? (
        <Editor
          docId={board.panel.docId}
          onSaved={() => void board.refresh()}
          onClose={() => board.setPanel({ kind: 'none' })}
        />
      ) : null}
      {board.panel.kind === 'terminal' ? <Terminal onClose={() => board.setPanel({ kind: 'none' })} /> : null}
    </div>
  )
}

export function Board() {
  return (
    <ReactFlowProvider>
      <Canvas />
    </ReactFlowProvider>
  )
}

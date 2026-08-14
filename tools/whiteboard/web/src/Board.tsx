import { Background, Controls, NodeToolbar, Position, ReactFlow, ReactFlowProvider, useReactFlow } from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { FileQuestionMark, LayoutDashboard, Search, TriangleAlert } from 'lucide-react'
import { useMemo, useState } from 'react'
import { Toaster } from 'sonner'
import type { DocNode } from '../../src/docRepository.ts'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { ResizableHandle, ResizablePanel, ResizablePanelGroup } from '@/components/ui/resizable'
import { useDefaultLayout } from 'react-resizable-panels'
import { TooltipProvider } from '@/components/ui/tooltip'
import { api } from './api.ts'
import { CommandPalette } from './CommandPalette.tsx'
import { Editor } from './Editor.tsx'
import { NODE_HEIGHT, NODE_WIDTH } from './layout.ts'
import { NodeCard } from './NodeCard.tsx'
import { Terminal } from './Terminal.tsx'
import { ThemeMenu } from './ThemeMenu.tsx'
import { Toolbar } from './Toolbar.tsx'
import { toFlowEdges, toFlowNodes } from './canvasModel.ts'
import { onFlowError } from './flowError.ts'
import { useTheme } from './theme.ts'
import { useBoard } from './useBoard.ts'

type DocNodeData = { node: DocNode; kind?: string }

const nodeTypes = {
  doc: ({ data, selected }: { data: DocNodeData; selected?: boolean }) => (
    <NodeCard node={data.node} kind={data.kind} selected={selected ?? false} />
  ),
}

function Canvas() {
  const board = useBoard()
  const theme = useTheme()
  const { setCenter } = useReactFlow()
  const [searching, setSearching] = useState(false)
  // v4 has no autoSaveId; this hook is the persistence path (localStorage by default).
  const rows = useDefaultLayout({ id: 'whiteboard-rows', panelIds: ['work', 'terminal'] })
  const columns = useDefaultLayout({ id: 'whiteboard-columns', panelIds: ['canvas', 'editor'] })

  const nodes = useMemo(() => {
    const laid = toFlowNodes(board.graph, board.placed, board.selected)
    return laid.map((node) => ({
      ...node,
      data: { ...(node.data as DocNodeData), kind: board.kinds[(node.data as DocNodeData).node.type ?? ''] },
    }))
  }, [board.graph, board.placed, board.selected, board.kinds])

  const edges = useMemo(() => toFlowEdges(board.graph, board.placed), [board.graph, board.placed])
  const selected = board.selectedNode

  /** Centre the viewport on a node and select it (spec-00001-FR-27). */
  function focus(id: string) {
    const at = board.placed.find((position) => position.id === id)
    if (at) setCenter(at.x + NODE_WIDTH / 2, at.y + NODE_HEIGHT / 2, { zoom: 1, duration: 300 })
    void board.select(id)
  }

  return (
    <div className="flex h-screen flex-col">
      <header className="flex items-center gap-3 border-b px-4 py-2">
        <LayoutDashboard className="size-5" aria-hidden />
        <strong className="text-sm">docs whiteboard</strong>

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
          <ResizablePanelGroup orientation="horizontal" {...columns}>
            <ResizablePanel id="canvas" defaultSize={board.editing ? 62 : 100} minSize={30}>
              <div className="relative h-full">
                <ReactFlow
                  nodes={nodes}
                  edges={edges}
                  nodeTypes={nodeTypes}
                  onNodeClick={(_event, node) => void board.select(node.id)}
                  onPaneClick={board.deselect}
                  // Handles exist to anchor edges, not to draw them: every edge
                  // comes from front matter (spec-00001-AC-1.14).
                  nodesConnectable={false}
                  onError={onFlowError}
                  fitView
                >
                  <Background />
                  <Controls />
                  {selected ? (
                    <NodeToolbar nodeId={selected.id} isVisible position={Position.Top}>
                      <Toolbar
                        node={selected}
                        transitions={board.transitions}
                        nextSteps={board.nextSteps}
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

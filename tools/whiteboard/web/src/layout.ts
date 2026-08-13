import ELK from 'elkjs/lib/elk.bundled.js'
import type { DocGraph } from '../../src/docRepository.ts'

export const NODE_WIDTH = 240
export const NODE_HEIGHT = 92

export interface Placed {
  id: string
  x: number
  y: number
}

const elk = new ELK()

/**
 * Layered top-down layout: the docs flow (idea -> prd -> spec -> plan) reads as
 * depth, so no one places a node by hand (spec-00001-AC-1.2).
 */
export async function layoutGraph(graph: DocGraph): Promise<Placed[]> {
  if (graph.nodes.length === 0) return []
  const laid = await elk.layout({
    id: 'root',
    layoutOptions: {
      'elk.algorithm': 'layered',
      'elk.direction': 'DOWN',
      'elk.spacing.nodeNode': '48',
      'elk.layered.spacing.nodeNodeBetweenLayers': '96',
    },
    children: graph.nodes.map((node) => ({ id: node.id, width: NODE_WIDTH, height: NODE_HEIGHT })),
    edges: graph.edges
      .filter((edge) => edge.ok)
      .map((edge, index) => ({ id: `e${index}`, sources: [edge.from], targets: [edge.to] })),
  })
  return (laid.children ?? []).map((child) => ({ id: child.id, x: child.x ?? 0, y: child.y ?? 0 }))
}

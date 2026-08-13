import type { DocGraph, DocNode } from './docRepository.ts'

/** What the board asked a session to produce; the yardstick for spec-00001-FR-17. */
export interface Expectation {
  targetType: string
  /** `<type>-<nnnnn>-` — the number is fixed, the agent picks the slug (rule-00001-BR-18). */
  idPrefix: string
  carry: string
  sourceId: string
}

/** The initial input handed to the agent CLI; its working directory is the docs tree. */
export function taskInstruction(expectation: Expectation): string {
  const { targetType, idPrefix, carry, sourceId } = expectation
  return [
    `Write one new ${targetType} document under ${targetType}/ in your working directory (the docs tree).`,
    `Give it the id ${idPrefix}<slug>: keep the number, choose the slug.`,
    `Its front matter must carry ${carry}: ${sourceId}.`,
    `Follow ${targetType}/TEMPLATE.md for front matter and ${targetType}/README.md for what belongs in it.`,
    'Leave status: draft — a human promotes it from the board.',
    'Change nothing outside the docs tree.',
  ].join('\n')
}

export function findProduct(graph: DocGraph, idPrefix: string): DocNode | undefined {
  return graph.nodes.find((node) => node.id.startsWith(idPrefix))
}

/** spec-00001-FR-17: the produced document is checked against what the session was asked for. */
export function productProblems(node: DocNode, expectation: Expectation): string[] {
  const problems = [...node.problems]
  if (node.type !== expectation.targetType) {
    problems.push(`type ${JSON.stringify(node.type)} is not the requested ${expectation.targetType}`)
  }
  if (!(node.relations[expectation.carry] ?? []).includes(expectation.sourceId)) {
    problems.push(`${expectation.carry} does not point at ${expectation.sourceId}`)
  }
  return problems
}

/** Mark the session's product anomalous in the graph the board renders. */
export function markProduct(graph: DocGraph, docId: string, problems: string[]): DocGraph {
  if (problems.length === 0) return graph
  return {
    ...graph,
    nodes: graph.nodes.map((node) =>
      node.id === docId ? { ...node, ok: false, problems: [...node.problems, ...problems] } : node,
    ),
    issues: [
      ...graph.issues,
      ...problems.map((message) => ({
        path: graph.nodes.find((node) => node.id === docId)?.path ?? docId,
        message,
      })),
    ],
  }
}

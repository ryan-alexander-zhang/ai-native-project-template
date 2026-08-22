import { isClarifiable } from './clarifyRules.ts'
import type { DocGraph, DocNode } from './docRepository.ts'

/** What the board asked a session to produce; the yardstick for spec-00001-FR-17. */
export interface Expectation {
  targetType: string
  /** `<type>-<nnnnn>-` — the number is fixed, the agent picks the slug (rule-00001-BR-18). */
  idPrefix: string
  carry: string
  sourceId: string
}

/**
 * The item grammar, restated for the agent that is about to write one of these
 * documents (spec-00001-FR-41). It is the「机器可读形态」section of the folder's
 * own README, said in the instruction rather than only linked, because the
 * board parses the body against it and reports what drifts (FR-40). A type
 * with no item grammar has no entry, and the instruction says nothing.
 */
const ITEM_GRAMMAR: Record<string, string[]> = {
  spec: [
    'Requirement items take one of two shapes, each starting its own line:',
    '  a list item `- **spec-<n>-FR-<i>** (<EARS type>) <text>`, its continuation lines indented; or',
    '  a decision-table row `| **spec-<n>-FR-<i>** | <cell> | … |`.',
    'Every acceptance criterion is a list item `- **spec-<n>-AC-<i>.<k>** (spec-<n>-FR-<i>)`,',
    '  with Given / When / Then on indented continuation lines. The attribution in',
    '  parentheses is required: a criterion without it belongs nowhere.',
    'Bold is the declaration form. Quote any id in prose — and every id belonging to',
    '  another document — in backticks, never in bold.',
  ],
  rule: [
    'Rules take one of two shapes, each starting its own line:',
    '  a list item `- **rule-<n>-BR-<i>** (<Kind>) <text>`, its continuation lines indented; or',
    '  a decision-table row `| **rule-<n>-BR-<i>** | <cell> | … |`.',
    'Every acceptance criterion is a list item `- **rule-<n>-AC-<i>.<k>** (rule-<n>-BR-<i>)`,',
    '  with Given / When / Then on indented continuation lines. The attribution in',
    '  parentheses is required: a criterion without it belongs nowhere.',
    'Bold is the declaration form. Quote any id in prose — and every id belonging to',
    '  another document — in backticks, never in bold.',
  ],
  record: [
    'The acceptance checklist is a table whose header names a test column and a result',
    '  column (Test/测试, Result/结果), neither of them the first column; an Evidence/证据',
    '  column is optional.',
    'The first cell of a checklist row is exactly one requirement or AC id. No ranges',
    '  (`AC-2.1 … AC-9.2`) and no two ids in one cell — one row, one id, so every row',
    '  can be checked on its own.',
  ],
}

/**
 * Carrying the upstream's unfinished business downstream (spec-00001-FR-11, the
 * thirteenth round). Advance has no Open Questions gate — a draft may be written
 * from an unsettled source — so the uncertainty has to travel with it, in writing,
 * instead of being absorbed by whoever writes the new document. Conditional the
 * way the item grammar is: only a target type that has an Open Questions section
 * to inherit into (the clarifiable set, rule-00001-BR-20) is told this.
 */
const OPEN_QUESTION_INHERITANCE = [
  'Read the unresolved Open Questions of the source document before you write, and inherit the ones',
  '  that still bind this new document into its own Open Questions section, stated in its own terms.',
  'Never silently decide one of them for the source: it stays an open question here, for its owner to settle.',
]

/** The initial input handed to the agent CLI; its working directory is the docs tree. */
export function taskInstruction(expectation: Expectation, sourcePath: string): string {
  const { targetType, idPrefix, carry, sourceId } = expectation
  const grammar = ITEM_GRAMMAR[targetType]
  return [
    `Write one new ${targetType} document under ${targetType}/ in your working directory (the docs tree).`,
    `Give it the id ${idPrefix}<slug>: keep the number, choose the slug.`,
    `Its front matter must carry ${carry}: ${sourceId}.`,
    `The source document is ${sourcePath} (relative to your working directory, the docs tree) — read it.`,
    `Follow ${targetType}/TEMPLATE.md for front matter and ${targetType}/README.md for what belongs in it.`,
    'Leave status: draft — a human promotes it from the board.',
    'Change nothing outside the docs tree.',
    ...(grammar === undefined
      ? []
      : [`Its body must follow the item grammar of ${targetType}/README.md (「机器可读形态」):`, ...grammar]),
    ...(isClarifiable(targetType) ? OPEN_QUESTION_INHERITANCE : []),
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
        nodeId: docId,
        message,
      })),
    ],
  }
}

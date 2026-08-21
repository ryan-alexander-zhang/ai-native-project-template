import { createHash } from 'node:crypto'
import { readFileSync, readdirSync } from 'node:fs'
import { basename, join } from 'node:path'
import matter from 'gray-matter'
import type { FlowConfig } from './config.ts'
import { type GraphDiagnostic, declaredIds, declaresItems, requirementViewFrom, scanRecords } from './requirements.ts'
import { isKnownStatus } from './statusRules.ts'

const EXCLUDED_FILES = new Set(['README.md', 'TEMPLATE.md'])
const ID_PATTERN = /^([a-z]+)-(\d{5})-([a-z0-9]+(?:-[a-z0-9]+)*)$/
const H1_PATTERN = /^#[ \t]+(.+?)[ \t]*$/m

export interface DocNode {
  /** Front matter id, or the repo-relative path when the document has none. */
  id: string
  path: string
  type?: string
  status?: string
  title: string
  /** Relation field -> the ids it declares, as written in the front matter. */
  relations: Record<string, string[]>
  ok: boolean
  problems: string[]
}

export interface DocEdge {
  from: string
  /** The document the edge lands on — the one holding the item, for a fine-grained reference. */
  to: string
  relation: string
  ok: boolean
  /** The ids the front matter actually declared; several of them share one edge (spec-00001-AC-28.5). */
  declaredTargets: string[]
}

export interface GraphIssue {
  path: string
  message: string
}

export interface DocGraph {
  nodes: DocNode[]
  edges: DocEdge[]
  issues: GraphIssue[]
  /** What drifted from the item grammar (spec-00001-FR-40); never an issue — a node stays sound. */
  diagnostics: GraphDiagnostic[]
}

interface ParsedDoc {
  path: string
  title: string
  /** The body without front matter — where the requirement items are declared. */
  body: string
  data: Record<string, unknown>
  parseError?: string
}

/** Repo-relative paths of the documents under `docsDir`, excluding README/TEMPLATE files. */
function listDocFiles(docsDir: string): string[] {
  let entries: string[]
  try {
    entries = readdirSync(docsDir, { recursive: true }) as string[]
  } catch {
    return []
  }
  return entries
    .filter((entry) => entry.endsWith('.md') && !EXCLUDED_FILES.has(basename(entry)))
    .map((entry) => entry.split(/[\\/]/).join('/'))
    .sort()
}

function parseDoc(docsDir: string, relPath: string): ParsedDoc {
  const raw = readFileSync(join(docsDir, relPath), 'utf8')
  try {
    const parsed = matter(raw)
    return {
      path: relPath,
      title: readTitle(parsed.content, relPath),
      body: parsed.content,
      data: parsed.data as Record<string, unknown>,
    }
  } catch (cause) {
    const parseError = (cause as Error).message
    return { path: relPath, title: readTitle(raw, relPath), body: raw, data: {}, parseError }
  }
}

/** The document's first H1, falling back to the file name (spec-00001-AC-1.5). */
function readTitle(body: string, relPath: string): string {
  return H1_PATTERN.exec(body)?.[1] ?? basename(relPath, '.md')
}

function frontMatterProblems(doc: ParsedDoc, config: FlowConfig): string[] {
  if (doc.parseError) return [`front matter is not valid YAML: ${doc.parseError}`]
  const { id, type, status } = doc.data
  if (id === undefined && type === undefined && status === undefined) return ['front matter is missing']

  const problems: string[] = []
  if (typeof type !== 'string' || !(type in config.types)) {
    problems.push(`type ${JSON.stringify(type)} is not a type in the flow config`)
  } else if (typeof status !== 'string' || !isKnownStatus(config.types[type]!, status)) {
    problems.push(`status ${JSON.stringify(status)} is not a status of a ${config.types[type]} document`)
  }
  problems.push(...idProblems(id, type))
  return problems
}

function idProblems(id: unknown, type: unknown): string[] {
  if (typeof id !== 'string') return ['front matter has no id']
  const match = ID_PATTERN.exec(id)
  if (!match) return [`id ${JSON.stringify(id)} does not match <type>-<nnnnn>-<slug>`]
  if (typeof type === 'string' && match[1] !== type) {
    return [`id ${JSON.stringify(id)} does not start with its type ${JSON.stringify(type)}`]
  }
  return []
}

function toNode(doc: ParsedDoc, config: FlowConfig): DocNode {
  const problems = frontMatterProblems(doc, config)
  const { id, type, status } = doc.data
  return {
    id: typeof id === 'string' ? id : doc.path,
    path: doc.path,
    type: typeof type === 'string' ? type : undefined,
    status: typeof status === 'string' ? status : undefined,
    title: doc.title,
    relations: Object.fromEntries(
      config.relations.map((relation) => [relation, declaredTargets(doc.data, relation)]),
    ),
    ok: problems.length === 0,
    problems,
  }
}

/** Relation targets declared by a document; `parent` is single-valued, the rest are lists. */
function declaredTargets(data: Record<string, unknown>, relation: string): string[] {
  const value = data[relation]
  if (typeof value === 'string') return [value]
  if (Array.isArray(value)) return value.filter((item): item is string => typeof item === 'string')
  return []
}

/**
 * Relation targets resolve in two stages (spec-00001-FR-2 as amended by
 * decision-00004 §5): a document id first, then a requirement item or criterion
 * id, which lands the edge on the document holding it. Only an id that is
 * neither is broken.
 *
 * Several declared ids of one field landing on one document share a single edge
 * — three lines drawn along one path are three lines nobody can tell apart
 * (spec-00001-AC-28.5); each declared id still appears in the relation list.
 */
function toEdges(node: DocNode, knownIds: Set<string>, itemOwners: Map<string, string>): DocEdge[] {
  return Object.entries(node.relations).flatMap(([relation, targets]) => {
    const groups = new Map<string, { ok: boolean; declaredTargets: string[] }>()
    for (const declared of targets) {
      const owner = knownIds.has(declared) ? declared : itemOwners.get(declared)
      const group = groups.get(owner ?? declared) ?? { ok: owner !== undefined, declaredTargets: [] }
      group.declaredTargets.push(declared)
      groups.set(owner ?? declared, group)
    }
    return [...groups].map(([to, group]) => ({ from: node.id, to, relation, ...group }))
  })
}

/** Which document each requirement item and acceptance criterion id belongs to. */
function itemOwners(docs: ParsedDoc[], nodes: DocNode[]): Map<string, string> {
  const owners = new Map<string, string>()
  // `nodes` is `docs` mapped one for one, so the index pairs a node with its body.
  nodes.forEach((node, index) => {
    if (!node.ok || !declaresItems(node.type)) return
    for (const id of declaredIds({ id: node.id, body: docs[index]!.body })) owners.set(id, node.id)
  })
  return owners
}

/**
 * The parse diagnostics of the whole tree (spec-00001-FR-40): every record is
 * scanned once, then each spec and rule is read against that scan — the same
 * derivation `/items` serves for one document, so the count in the top bar and
 * the rows in the panel can never disagree.
 */
function graphDiagnostics(docs: ParsedDoc[], nodes: DocNode[]): GraphDiagnostic[] {
  const body = (index: number) => docs[index]!.body
  const scan = scanRecords(
    nodes.flatMap((node, index) => (node.type === 'record' ? [{ id: node.id, body: body(index) }] : [])),
  )
  return nodes.flatMap((node, index) => {
    if (!node.ok || !declaresItems(node.type)) return []
    const view = requirementViewFrom({ id: node.id, body: body(index) }, scan)
    return view.diagnostics.map((diagnostic) => ({ docId: node.id, ...diagnostic }))
  })
}

function buildGraph(docs: ParsedDoc[], config: FlowConfig): DocGraph {
  const nodes = docs.map((doc) => toNode(doc, config))
  const knownIds = new Set(nodes.filter((node) => node.ok).map((node) => node.id))
  const owners = itemOwners(docs, nodes)
  const edges = nodes.flatMap((node) => toEdges(node, knownIds, owners))

  const issues: GraphIssue[] = [
    ...nodes.flatMap((node) => node.problems.map((message) => ({ path: node.path, message }))),
    ...edges
      .filter((edge) => !edge.ok)
      .map((edge) => ({
        path: nodes.find((node) => node.id === edge.from)!.path,
        message: `${edge.relation} points at unknown document ${JSON.stringify(edge.to)}`,
      })),
  ]
  return { nodes, edges, issues, diagnostics: graphDiagnostics(docs, nodes) }
}

/** Scan `docsDir` and build the node graph. An unreadable or empty directory yields an empty graph. */
export function readGraph(docsDir: string, config: FlowConfig): DocGraph {
  return buildGraph(
    listDocFiles(docsDir).map((relPath) => parseDoc(docsDir, relPath)),
    config,
  )
}

export function contentHash(content: string): string {
  return createHash('sha256').update(content).digest('hex')
}

export interface DocContent {
  path: string
  content: string
  hash: string
}

/** Read a document's whole file, front matter included — the editor edits the raw text. */
export function readDocContent(docsDir: string, node: DocNode): DocContent {
  const content = readFileSync(join(docsDir, node.path), 'utf8')
  return { path: node.path, content, hash: contentHash(content) }
}

/** A document's body without its front matter — what the item parser reads. */
export function readDocBody(docsDir: string, node: DocNode): string {
  return parseDoc(docsDir, node.path).body
}

/**
 * The id a raw file declares, before it is a document in the graph at all — what
 * the create path checks the submitted content against (spec-00001-FR-53).
 * Unreadable front matter declares nothing.
 */
export function frontMatterId(content: string): string | undefined {
  try {
    const { id } = matter(content).data as { id?: unknown }
    return typeof id === 'string' ? id : undefined
  } catch {
    return undefined
  }
}

export function findNode(graph: DocGraph, id: string): DocNode | undefined {
  return graph.nodes.find((node) => node.id === id)
}

export interface DocId {
  type: string
  number: number
  slug: string
}

/**
 * An id read back into its three parts (rule-00001-BR-18), or nothing when it is
 * not one. The slug half of the pattern is what refuses an upper-case letter or
 * a space in a hand-typed slug (spec-00001-AC-53.4).
 */
export function parseDocId(id: string): DocId | undefined {
  const match = ID_PATTERN.exec(id)
  return match ? { type: match[1]!, number: Number(match[2]), slug: match[3]! } : undefined
}

/** Highest five-digit number already used by documents of `type`, or 0 when there are none. */
export function highestNumber(graph: DocGraph, type: string): number {
  const numbers = graph.nodes
    .map((node) => ID_PATTERN.exec(node.id))
    .filter((match) => match?.[1] === type)
    .map((match) => Number(match![2]))
  return numbers.length === 0 ? 0 : Math.max(...numbers)
}

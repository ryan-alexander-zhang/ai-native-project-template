import { createHash } from 'node:crypto'
import { readFileSync, readdirSync } from 'node:fs'
import { basename, join } from 'node:path'
import matter from 'gray-matter'
import type { FlowConfig } from './config.ts'
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
  to: string
  relation: string
  ok: boolean
}

export interface GraphIssue {
  path: string
  message: string
}

export interface DocGraph {
  nodes: DocNode[]
  edges: DocEdge[]
  issues: GraphIssue[]
}

interface ParsedDoc {
  path: string
  title: string
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
    return { path: relPath, title: readTitle(parsed.content, relPath), data: parsed.data as Record<string, unknown> }
  } catch (cause) {
    return { path: relPath, title: readTitle(raw, relPath), data: {}, parseError: (cause as Error).message }
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

function toEdges(node: DocNode, knownIds: Set<string>): DocEdge[] {
  return Object.entries(node.relations).flatMap(([relation, targets]) =>
    targets.map((to) => ({ from: node.id, to, relation, ok: knownIds.has(to) })),
  )
}

function buildGraph(docs: ParsedDoc[], config: FlowConfig): DocGraph {
  const nodes = docs.map((doc) => toNode(doc, config))
  const knownIds = new Set(nodes.filter((node) => node.ok).map((node) => node.id))
  const edges = nodes.flatMap((node) => toEdges(node, knownIds))

  const issues: GraphIssue[] = [
    ...nodes.flatMap((node) => node.problems.map((message) => ({ path: node.path, message }))),
    ...edges
      .filter((edge) => !edge.ok)
      .map((edge) => ({
        path: nodes.find((node) => node.id === edge.from)!.path,
        message: `${edge.relation} points at unknown document ${JSON.stringify(edge.to)}`,
      })),
  ]
  return { nodes, edges, issues }
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

export function findNode(graph: DocGraph, id: string): DocNode | undefined {
  return graph.nodes.find((node) => node.id === id)
}

/** Highest five-digit number already used by documents of `type`, or 0 when there are none. */
export function highestNumber(graph: DocGraph, type: string): number {
  const numbers = graph.nodes
    .map((node) => ID_PATTERN.exec(node.id))
    .filter((match) => match?.[1] === type)
    .map((match) => Number(match![2]))
  return numbers.length === 0 ? 0 : Math.max(...numbers)
}

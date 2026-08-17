import { readFileSync, rmSync } from 'node:fs'
import { join } from 'node:path'
import type { DocGraph } from './docRepository.ts'

/**
 * What a clarify session is told (spec-00001-FR-45 and FR-46 are its contract:
 * the context paths, the shared questioning skeleton, the focus line, the state
 * file and the closing requirements). This build is the minimum a session needs
 * to run; the wording those two requirements demand is filled in by
 * plan-00009 W2 — read them, not this text, as the contract.
 */
export interface ClarifyTask {
  /** The document being clarified, relative to the session's working directory (the docs tree). */
  docPath: string
  /** Its relation documents in both directions, same relativity; empty when it has none. */
  relatedPaths: string[]
  /** The focus line of its type (spec-00001-FR-48). */
  focus: string
  /** The clarify state file, relative to the repo root (spec-00001-FR-46). */
  statePath: string
  /** Progress left by an earlier session, when there is any to recover from (spec-00001-FR-46). */
  state?: string
}

/** What an ask session is told; its contract is spec-00001-FR-47, filled in by plan-00009 W2. */
export interface AskTask {
  docPath: string
  relatedPaths: string[]
}

function contextLines(docPath: string, relatedPaths: string[]): string[] {
  return [
    `The document: ${docPath} (relative to your working directory, the docs tree).`,
    ...(relatedPaths.length === 0
      ? []
      : [`Its relation documents, for context — read them as you need them: ${relatedPaths.join(', ')}`]),
  ]
}

export function clarifyInstruction(task: ClarifyTask): string {
  const { docPath, relatedPaths, focus, statePath, state } = task
  return [
    'This is a clarify session (澄清): you question the owner of one document, one question at a time,',
    'and land what you learn back in that document.',
    ...contextLines(docPath, relatedPaths),
    `What to weigh your questions on: ${focus}`,
    `Keep the question progress in ${statePath} (relative to the repository root).`,
    ...(state === undefined ? [] : ['Already answered — do not ask any of it again:', state]),
    'Leave the status line as it is, and change nothing outside the docs tree.',
  ].join('\n')
}

export function askInstruction(task: AskTask): string {
  const { docPath, relatedPaths } = task
  return [
    'This is an ask session (答疑): the owner of one document asks you about it and discusses it with you',
    'over as many turns as they need.',
    ...contextLines(docPath, relatedPaths),
    'Answer what they ask. Revise documents under the docs tree when the conversation concludes one should',
    'change, and never touch a status line — status changes belong to the board.',
    'Change nothing outside the docs tree.',
  ].join('\n')
}

/** Where a clarify session keeps its progress, relative to the repo root (spec-00001-FR-46). */
export function clarifyStatePath(docId: string): string {
  return `.whiteboard/clarify/${docId}.json`
}

/**
 * The progress an earlier clarify session left, or nothing (spec-00001-FR-46).
 * A file that is not valid JSON counts as nothing: the session starts over and
 * overwrites it, rather than handing an agent a broken record to recover from.
 */
export function readClarifyState(repoRoot: string, docId: string): string | undefined {
  let text: string
  try {
    text = readFileSync(join(repoRoot, clarifyStatePath(docId)), 'utf8')
  } catch {
    return undefined
  }
  try {
    JSON.parse(text)
  } catch {
    return undefined
  }
  return text
}

/** Drop the state file an accept made pointless (spec-00001-AC-46.6); no file is no work. */
export function removeClarifyState(repoRoot: string, docId: string): void {
  rmSync(join(repoRoot, clarifyStatePath(docId)), { force: true })
}

/**
 * The paths of a document's relation documents, both directions (spec-00001-FR-45
 * and FR-47): the graph has already resolved every declared id — including a
 * fine-grained item id, which lands on the document holding it — so an edge that
 * did not resolve is simply not context, and a document referring to itself adds
 * no second path.
 */
export function relatedDocPaths(graph: DocGraph, docId: string): string[] {
  const pathOf = new Map(graph.nodes.map((node) => [node.id, node.path]))
  const related = new Set<string>()
  for (const edge of graph.edges) {
    if (!edge.ok) continue
    if (edge.from === docId && edge.to !== docId) related.add(pathOf.get(edge.to)!)
    if (edge.to === docId && edge.from !== docId) related.add(pathOf.get(edge.from)!)
  }
  return [...related].sort()
}

import { readFileSync, rmSync } from 'node:fs'
import { join } from 'node:path'
import type { DocGraph } from './docRepository.ts'

/**
 * What a clarify session is told (spec-00001-FR-45 and FR-46 are its contract:
 * the context paths, the shared questioning skeleton, the focus line, the state
 * file and the closing requirements).
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

/** What an ask session is told; its contract is spec-00001-FR-47. */
export interface AskTask {
  docPath: string
  relatedPaths: string[]
}

/** What an audit session is told; its contract is spec-00001-FR-50 with rule-00001-BR-22. */
export interface AuditTask {
  /** The document being audited, relative to the session's working directory (the docs tree). */
  docPath: string
  /** Its type folder's README, same relativity: the conventions the structure pass is held against. */
  readmePath: string
}

/** Paths only, both requirements say (FR-45, FR-47): the session reads the bodies itself. */
function contextLines(docPath: string, relatedPaths: string[]): string[] {
  return [
    `The document: ${docPath} (relative to your working directory, the docs tree).`,
    ...(relatedPaths.length === 0
      ? []
      : [`Its relation documents, for context: ${relatedPaths.join(', ')}`]),
    'These are paths, not content — read whichever of them you need, as you need them.',
  ]
}

/**
 * The shared questioning skeleton (spec-00001-FR-45, decision-00006 §2): code
 * holds it whole, the flow config holds only the focus line, so no configuration
 * can wear it down.
 */
const SKELETON = [
  'Ask one question per turn — never a batch, never two questions in one.',
  'Give each question at most 4 ready-made options, the one you recommend first and marked "Recommended",',
  '  and always leave a free-form answer open.',
  'Whatever you can answer from the documents or the repository, answer yourself; ask only what',
  '  the owner alone can settle.',
]

/**
 * The closing (spec-00001-FR-45 with rule-00001-BR-11). Locating the Open
 * Questions section is stated here rather than done by the board, because since
 * the eighth round clarify has no server-side write-back (design-00001 §6).
 */
const CLOSING = [
  'When you are done: append every open point the answers confirmed to the Open Questions section of',
  '  that document — find the heading by name, case-insensitively and allowing a numbered form',
  '  (`## 6. Open Questions`); only if there is none, create the section at the end of the file;',
  '  never create a second one.',
  'Keep status: draft — a human promotes it from the board.',
  'Where an answer settled the matter, revise the body itself instead of leaving a question behind.',
]

/**
 * The state file contract (spec-00001-FR-46). The requirement fixes the path
 * against the repository root, while the session stands in the docs tree, so the
 * instruction gives it from there — the agent has no root to guess at.
 */
function stateFileLines(statePath: string, state: string | undefined): string[] {
  return [
    `Keep the question progress in ../${statePath} — the repository root is one level above your`,
    '  working directory. Write it as soon as a question is answered (what has been asked and',
    '  answered, what is still to ask), and delete it once every conclusion is on disk.',
    ...(state === undefined
      ? []
      : [
          'Recover from the progress below — you asked these and were answered; ask none of them again:',
          state,
        ]),
  ]
}

export function clarifyInstruction(task: ClarifyTask): string {
  const { docPath, relatedPaths, focus, statePath, state } = task
  return [
    'This is a clarify session: you question the owner of one document, one question at a time,',
    'and land what you learn back in that document.',
    ...contextLines(docPath, relatedPaths),
    ...SKELETON,
    `What to weigh your questions on: ${focus}`,
    // The convergence call (spec-00001-FR-45, twelfth round). It follows the
    // focus line rather than joining SKELETON, because the focus line is what
    // defines this stage's advance decision and carries its stop condition.
    "Settle this stage's advance decision with the fewest questions that do it.",
    'Once the remaining questions would no longer change that decision — the stop condition the focus',
    '  line above names — declare the clarification saturated and move to the closing instead of',
    '  asking on.',
    ...stateFileLines(statePath, state),
    ...CLOSING,
    'Change nothing outside the docs tree, other than that progress file.',
  ].join('\n')
}

export function askInstruction(task: AskTask): string {
  const { docPath, relatedPaths } = task
  return [
    'This is an ask session: the owner of one document asks you about it and discusses it with you',
    'over as many turns as they need.',
    ...contextLines(docPath, relatedPaths),
    'Answer what they ask about this document. Revise documents under the docs tree when they ask you to,',
    'or when the conversation concludes one should change.',
    'Never touch a status line — status changes belong to the board, to a transition or a review action.',
    'Change nothing outside the docs tree.',
  ].join('\n')
}

/**
 * The audit session (spec-00001-FR-50 with rule-00001-BR-22). Two things make it
 * an audit rather than a second read: the stance — the reviewer did not write
 * this, and owes the existing wording no defence — and the two passes, the folder
 * README's conventions before the content. The findings land in the document
 * itself, which is what puts them in front of the accept gate of BR-12; audit
 * keeps no progress file, so not re-appending what the section already lists is
 * stated here rather than remembered across sessions.
 */
export function auditInstruction(task: AuditTask): string {
  const { docPath, readmePath } = task
  return [
    'This is an audit session: you review one document as somebody who did not write it, and land',
    'what you find back in that document.',
    `The document: ${docPath} (relative to your working directory, the docs tree).`,
    `The conventions it is held to: ${readmePath}, the README of its own folder.`,
    'These are paths, not content — read both yourself.',
    'Review from the stance of someone who did NOT write this document: never defend the existing',
    '  wording, and take nothing in it on trust.',
    `Go in two passes: first the structure and the grammar ${readmePath} lays down, then the content`,
    '  itself.',
    'List, one by one: every rule, case and GWT that is missing; every reading the document took',
    '  silently; and every value you cannot confirm.',
    'Append each unresolved finding as a list item to the Open Questions section of that document —',
    '  find the heading by name, case-insensitively and allowing a numbered form',
    '  (`## 6. Open Questions`); only if there is none, create the section at the end of the file;',
    '  never create a second one.',
    'Read what that section already holds before you write: a finding it already lists is not',
    '  appended again.',
    'Where a finding is already settled, amend the body itself instead of leaving a question behind.',
    'Never touch the status line — the document stays as it is, and status moves only from the board.',
    'Change nothing outside the docs tree.',
  ].join('\n')
}

/** The folder README an audit is held to, relative to the docs tree (spec-00001-FR-50). */
export function typeReadmePath(type: string): string {
  return `${type}/README.md`
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

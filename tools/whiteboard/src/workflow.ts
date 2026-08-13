import type { FlowConfig, FlowStep } from './config.ts'
import type { DocGraph, DocNode } from './docRepository.ts'
import { highestNumber } from './docRepository.ts'
import { allowedTransitions, promotedStatus } from './statusRules.ts'

const OPEN_QUESTIONS_HEADING = /^#{1,6}\s+(?:\d+\.\s*)?open questions\s*$/i
const ANY_HEADING = /^#{1,6}\s/
const LIST_ITEM = /^\s*(?:[-*]|\d+\.)\s+\S/
const STATUS_LINE = /^status:\s*\S.*$/

/** A rejected action; the message states why the workflow refused it. */
export class WorkflowError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'WorkflowError'
  }
}

function kindOf(node: DocNode, config: FlowConfig) {
  if (!node.ok || node.type === undefined || node.status === undefined) {
    throw new WorkflowError(`${node.id} has front matter problems and takes no workflow action`)
  }
  return config.types[node.type]!
}

/** Target statuses the user may pick (spec-00001-FR-6); empty for an anomalous document. */
export function transitionsFor(node: DocNode, config: FlowConfig): string[] {
  if (!node.ok || node.type === undefined || node.status === undefined) return []
  return allowedTransitions(config.types[node.type]!, node.status)
}

/** Next-step document types (spec-00001-FR-10); empty when the flow config declares none. */
export function nextStepsFor(node: DocNode, config: FlowConfig): FlowStep[] {
  if (!node.ok || node.type === undefined) return []
  return config.flow[node.type] ?? []
}

/** Front matter status line replaced in place — the rest of the file is untouched. */
function replaceStatus(content: string, to: string): string {
  const lines = content.split('\n')
  const end = lines.indexOf('---', 1)
  const at = lines.findIndex((line, i) => i > 0 && i < end && STATUS_LINE.test(line))
  if (at === -1) throw new WorkflowError('front matter has no status line to change')
  lines[at] = `status: ${to}`
  return lines.join('\n')
}

/** spec-00001-FR-7: only a transition the table allows is written. */
export function applyStatusChange(content: string, node: DocNode, config: FlowConfig, to: string): string {
  const kind = kindOf(node, config)
  if (!allowedTransitions(kind, node.status!).includes(to)) {
    throw new WorkflowError(`${node.status} -> ${to} is not a legal transition for a ${kind} document`)
  }
  return replaceStatus(content, to)
}

/** spec-00001-FR-8 with rule-00001-BR-10 and BR-12. */
export function applyAccept(content: string, node: DocNode, config: FlowConfig): { content: string; to: string } {
  const kind = kindOf(node, config)
  if (node.status !== 'draft') {
    throw new WorkflowError(`accept applies to a draft document; ${node.id} is ${node.status}`)
  }
  if (hasOpenQuestions(content)) {
    throw new WorkflowError(`${node.id} has unresolved open questions and cannot be accepted`)
  }
  const to = promotedStatus(kind)
  return { content: replaceStatus(content, to), to }
}

/** spec-00001-FR-9 with rule-00001-BR-11: the document stays draft. */
export function applyClarify(content: string, node: DocNode, config: FlowConfig, questions: string[]): string {
  kindOf(node, config)
  if (node.status !== 'draft') {
    throw new WorkflowError(`clarify applies to a draft document; ${node.id} is ${node.status}`)
  }
  if (questions.length === 0) {
    throw new WorkflowError('clarify needs at least one question')
  }
  return appendOpenQuestions(content, questions)
}

interface Section {
  /** Index of the heading line. */
  heading: number
  /** Index one past the last line belonging to the section. */
  end: number
}

function findOpenQuestions(lines: string[]): Section | undefined {
  const heading = lines.findIndex((line) => OPEN_QUESTIONS_HEADING.test(line))
  if (heading === -1) return undefined
  const next = lines.findIndex((line, i) => i > heading && ANY_HEADING.test(line))
  return { heading, end: next === -1 ? lines.length : next }
}

/**
 * rule-00001-BR-12: a section carrying at least one list item is unresolved.
 * Templates say the section is deleted once every question is closed.
 */
export function hasOpenQuestions(content: string): boolean {
  const lines = content.split('\n')
  const section = findOpenQuestions(lines)
  if (!section) return false
  return lines.slice(section.heading + 1, section.end).some((line) => LIST_ITEM.test(line))
}

function appendOpenQuestions(content: string, questions: string[]): string {
  const items = questions.map((question) => `- ${question}`)
  const lines = content.split('\n')
  const section = findOpenQuestions(lines)
  if (!section) {
    return `${content.replace(/\s*$/, '')}\n\n## Open Questions\n\n${items.join('\n')}\n`
  }
  let at = section.end
  while (at > section.heading + 1 && lines[at - 1]!.trim() === '') at--
  lines.splice(at, 0, ...items)
  return lines.join('\n')
}

/** rule-00001-BR-18: the number a new document of `type` takes. */
export function allocateNumber(graph: DocGraph, type: string): number {
  return highestNumber(graph, type) + 1
}

/** The `<type>-<nnnnn>-` prefix a new document must carry; the agent picks the slug. */
export function idPrefix(type: string, count: number): string {
  return `${type}-${String(count).padStart(5, '0')}-`
}

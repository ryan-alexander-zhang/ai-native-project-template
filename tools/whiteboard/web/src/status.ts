import {
  BookMarked,
  Bug,
  ChartLine,
  ClipboardCheck,
  DraftingCompass,
  File,
  FileChartColumn,
  FileText,
  Gavel,
  Lightbulb,
  ListChecks,
  type LucideIcon,
  MessageSquare,
  Plug,
  Scale,
  SquareCheck,
  Target,
  Wrench,
} from 'lucide-react'
import type { DocNode } from '../../src/docRepository.ts'

/** Status token per rule-00001's vocabulary; the values live in index.css. */
const STATUS_TOKENS: Record<string, string> = {
  draft: 'var(--status-draft)',
  active: 'var(--status-active)',
  open: 'var(--status-open)',
  resolved: 'var(--status-resolved)',
  wontfix: 'var(--status-wontfix)',
  archived: 'var(--status-archived)',
}

/** An anomaly is not a status, so it takes the destructive token instead. */
export const ANOMALY_TOKEN = 'var(--destructive)'

export function statusColour(node: DocNode): string {
  if (!node.ok) return ANOMALY_TOKEN
  return STATUS_TOKENS[node.status ?? ''] ?? ANOMALY_TOKEN
}

export function statusLabel(node: DocNode): string {
  return node.ok ? (node.status ?? '') : 'front matter problem'
}

const TYPE_ICONS: Record<string, LucideIcon> = {
  idea: Lightbulb,
  prd: Target,
  spec: FileText,
  rule: Scale,
  design: DraftingCompass,
  decision: Gavel,
  plan: ListChecks,
  task: SquareCheck,
  issue: Bug,
  record: ClipboardCheck,
  analysis: ChartLine,
  integration: Plug,
  reference: BookMarked,
  operation: Wrench,
  prompt: MessageSquare,
  report: FileChartColumn,
}

/** A type the flow config carries but this map does not falls back to a plain file. */
export function typeIcon(type: string | undefined): LucideIcon {
  return TYPE_ICONS[type ?? ''] ?? File
}

/** living / work drives the node's outline; the kind comes from the flow config. */
export function kindColour(kind: string | undefined): string {
  if (kind === 'living') return 'var(--kind-living)'
  if (kind === 'work') return 'var(--kind-work)'
  return 'var(--border)'
}

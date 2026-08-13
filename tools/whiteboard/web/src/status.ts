import type { DocNode } from '../../src/docRepository.ts'

/** One colour per status so the board's state reads at a glance (spec-00001 §7). */
const STATUS_COLOURS: Record<string, string> = {
  draft: '#9aa0a6',
  active: '#2e7d32',
  open: '#1565c0',
  resolved: '#6a1b9a',
  wontfix: '#8d6e63',
  archived: '#5f6368',
}

export const ANOMALY_COLOUR = '#c62828'

export function statusColour(node: DocNode): string {
  if (!node.ok) return ANOMALY_COLOUR
  return STATUS_COLOURS[node.status ?? ''] ?? ANOMALY_COLOUR
}

export function statusLabel(node: DocNode): string {
  return node.ok ? (node.status ?? '') : 'front matter problem'
}

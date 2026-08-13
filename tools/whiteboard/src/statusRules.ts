import type { DocKind } from './config.ts'

/**
 * The status transition table of rule-00001-BR-2 … BR-9, keyed by document kind
 * and current status. Derived from `kind`, never configured — a combination the
 * table does not carry is illegal and allows no transition (BR-9).
 */
const TRANSITIONS: Record<DocKind, Record<string, readonly string[]>> = {
  living: {
    draft: ['active', 'archived'],
    active: ['archived'],
    archived: [],
  },
  work: {
    draft: ['open', 'wontfix', 'archived'],
    open: ['resolved', 'wontfix', 'archived'],
    resolved: ['archived'],
    wontfix: ['archived'],
    archived: [],
  },
}

/** Target statuses reachable from `status`; empty for a terminal or illegal combination. */
export function allowedTransitions(kind: DocKind, status: string): string[] {
  return [...(TRANSITIONS[kind][status] ?? [])]
}

/** Whether `status` belongs to the vocabulary of `kind` (rule-00001-BR-9's otherwise row). */
export function isKnownStatus(kind: DocKind, status: string): boolean {
  return status in TRANSITIONS[kind]
}

/** The status a `draft` document of this kind is promoted to on accept (rule-00001-BR-10). */
export function promotedStatus(kind: DocKind): string {
  return kind === 'living' ? 'active' : 'open'
}

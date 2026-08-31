/**
 * The auditable types of rule-00001-BR-23 — the types whose folder README lays
 * down a structure to audit against: the item grammar and GWTs of spec and rule,
 * the trade-offs and module boundaries of design. Built into the code the way the
 * clarifiable set is (clarifyRules.ts): the audit's yardstick is the folder
 * README, which is not a configurable fact (spec-00001-FR-50).
 */
const AUDITABLE: readonly string[] = ['spec', 'rule', 'design']

/** The set itself, for the effective-config payload the front end reads (spec-00001-FR-56). */
export function auditableTypes(): string[] {
  return [...AUDITABLE]
}

/** Whether audit applies to a document of this type at all (rule-00001-BR-23). */
export function isAuditable(type: string | undefined): boolean {
  return type !== undefined && AUDITABLE.includes(type)
}

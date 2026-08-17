/**
 * The clarifiable types of rule-00001-BR-20 — the types that carry intent or a
 * decision, and therefore have business questions to ask. Built into the code
 * the way the status transition table is (spec-00001-FR-48): the flow config
 * holds one focus line per type in this set, never the set itself.
 */
const CLARIFIABLE: readonly string[] = ['idea', 'prd', 'spec', 'rule', 'design']

/** The set itself, for the startup check that every one of them carries a focus line. */
export function clarifiableTypes(): string[] {
  return [...CLARIFIABLE]
}

/** Whether clarify applies to a document of this type at all (rule-00001-BR-20). */
export function isClarifiable(type: string | undefined): boolean {
  return type !== undefined && CLARIFIABLE.includes(type)
}

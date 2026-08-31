/**
 * The gate on a plan's `open → resolved` (spec-00001-FR-52, rule-00001-BR-24 and
 * BR-25): the plan declares its delivery scope in `implements`, and the records
 * that name it are the only evidence allowed to close it.
 *
 * Coverage is not re-derived here. The verdict comes from the same
 * `requirements.ts` derivation `/items` serves (design-00001 §2), handed the
 * narrowed record set — the gate changes the evidence, never the reading, so the
 * panel and the gate can never disagree about an item.
 */

import { type DocBody, type RequirementItem, requirementViewFrom, scanRecords } from './requirements.ts'

/** One item-declaring document (spec or rule), its items read against one evidence set. */
export interface ItemDoc {
  id: string
  items: RequirementItem[]
}

/** A plan's delivery scope (rule-00001-BR-24), and the targets that named nothing at all. */
export interface DeliveryScope {
  /** The requirement items the plan must have verified, in the order `implements` reached them. */
  items: string[]
  /** Targets that are neither an existing document nor an existing item or criterion (BR-25). */
  unresolved: string[]
}

/**
 * Every item-declaring document read against `records` alone — the evidence set
 * of rule-00001-BR-25. The records are scanned once for all of them, as the
 * graph-wide pass does.
 */
export function itemCoverage(docs: readonly DocBody[], records: readonly DocBody[]): ItemDoc[] {
  const scan = scanRecords([...records])
  return docs.map((doc) => ({ id: doc.id, items: requirementViewFrom(doc, scan).items }))
}

/**
 * rule-00001-BR-24: what each `implements` target contributes to the scope — an
 * item id itself, an acceptance criterion's owning item, a whole spec or rule's
 * every item. A target naming a document of any other type contributes nothing
 * and is no gap; a target naming nothing in the repo is one.
 */
export function deliveryScope(
  targets: readonly string[],
  docIds: readonly string[],
  docs: readonly ItemDoc[],
): DeliveryScope {
  const known = new Set(docIds)
  const items: string[] = []
  const unresolved: string[] = []
  for (const target of targets) {
    const whole = docs.find((doc) => doc.id === target)
    if (whole) {
      items.push(...whole.items.map((item) => item.id))
      continue
    }
    const owner = owningItem(docs, target)
    if (owner !== undefined) items.push(owner)
    else if (!known.has(target)) unresolved.push(target)
  }
  return { items: unique(items), unresolved: unique(unresolved) }
}

/**
 * rule-00001-BR-25: the gaps that refuse the transition, named one by one —
 * every scope item whose coverage is not `verified`, and every target that
 * resolved to nothing. Empty is the gate letting the transition through, which
 * an empty scope always is.
 */
export function resolvedGaps(
  targets: readonly string[],
  docIds: readonly string[],
  docs: readonly ItemDoc[],
): string[] {
  const scope = deliveryScope(targets, docIds, docs)
  const coverage = new Map(docs.flatMap((doc) => doc.items.map((item) => [item.id, item.coverage] as const)))
  return [...scope.items.filter((id) => coverage.get(id) !== 'verified'), ...scope.unresolved]
}

/** The item a target belongs to: the item itself, or the item holding that criterion. */
function owningItem(docs: readonly ItemDoc[], target: string): string | undefined {
  for (const doc of docs) {
    const owner = doc.items.find(
      (item) => item.id === target || item.criteria.some((criterion) => criterion.id === target),
    )
    if (owner) return owner.id
  }
  return undefined
}

function unique(ids: string[]): string[] {
  return [...new Set(ids)]
}

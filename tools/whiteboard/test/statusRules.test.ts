import { describe, expect, it } from 'vitest'
import { allowedTransitions, isKnownStatus, promotedStatus } from '../src/statusRules.ts'

// rule-00001-BR-2 … BR-9: the transition table, one case per row.
describe('allowedTransitions', () => {
  // rule-00001-AC-2.1 (a draft design document)
  it('offers active and archived from a draft living doc', () => {
    expect(allowedTransitions('living', 'draft')).toEqual(['active', 'archived'])
  })

  // rule-00001-AC-3.1 (an active decision document) — `draft` is the revision
  // round, added in the eleventh round (decision-00008 §2 第 1 条)
  it('offers draft and archived from an active living doc', () => {
    expect(allowedTransitions('living', 'active')).toEqual(['draft', 'archived'])
  })

  // rule-00001-AC-4.1 (a draft plan)
  it('offers open, wontfix, and archived from a draft work item', () => {
    expect(allowedTransitions('work', 'draft')).toEqual(['open', 'wontfix', 'archived'])
  })

  // rule-00001-AC-5.1 (an open issue)
  it('offers resolved, wontfix, and archived from an open work item', () => {
    expect(allowedTransitions('work', 'open')).toEqual(['resolved', 'wontfix', 'archived'])
  })

  // rule-00001-AC-6.1 (a resolved task)
  it('offers archived from a resolved work item', () => {
    expect(allowedTransitions('work', 'resolved')).toEqual(['archived'])
  })

  // rule-00001-AC-7.1 (a wontfix issue)
  it('offers archived from a wontfix work item', () => {
    expect(allowedTransitions('work', 'wontfix')).toEqual(['archived'])
  })

  // rule-00001-AC-8.1 (an archived idea) — archived is terminal for both kinds
  it('offers nothing from archived', () => {
    expect(allowedTransitions('living', 'archived')).toEqual([])
    expect(allowedTransitions('work', 'archived')).toEqual([])
  })

  // rule-00001-AC-9.1 — a status outside the vocabulary
  it('offers nothing for a status outside the vocabulary', () => {
    expect(allowedTransitions('living', 'review')).toEqual([])
  })

  // rule-00001-AC-9.2 — a work-item status on a living doc
  it('offers nothing for a status from the other kind vocabulary', () => {
    expect(allowedTransitions('living', 'open')).toEqual([])
    expect(allowedTransitions('work', 'active')).toEqual([])
  })

  it('returns a fresh list the caller cannot use to mutate the table', () => {
    allowedTransitions('living', 'draft').push('open')
    expect(allowedTransitions('living', 'draft')).toEqual(['active', 'archived'])
  })
})

describe('isKnownStatus', () => {
  it('accepts statuses of the matching kind', () => {
    expect(isKnownStatus('living', 'active')).toBe(true)
    expect(isKnownStatus('work', 'resolved')).toBe(true)
  })

  it('rejects statuses of the other kind and unknown words', () => {
    expect(isKnownStatus('living', 'resolved')).toBe(false)
    expect(isKnownStatus('work', 'active')).toBe(false)
    expect(isKnownStatus('living', 'review')).toBe(false)
  })
})

// rule-00001-BR-10
describe('promotedStatus', () => {
  it('promotes a living doc to active', () => {
    expect(promotedStatus('living')).toBe('active')
  })

  it('promotes a work item to open', () => {
    expect(promotedStatus('work')).toBe('open')
  })
})

/**
 * Which directory groups are open, in the same local layer the navigation
 * sidebar's two preferences live in (design-00002 §17.1, §19.3). It is view
 * state, but it survives a reload: it is the user's preference about the
 * interface, not their place in a document. Nothing here reaches `docs/`.
 *
 * The value is the expand keys — column key + NUL + group key, as `layout.ts`
 * builds them — so a renamed directory, or a document that changed `type` and
 * with it its column, leaves its old key behind and the group starts collapsed
 * again (spec-00010-FR-9). An expired key stays in storage: it is harmless, and
 * clearing it would mean comparing the whole set against the graph on every
 * refresh.
 */

const EXPANDED_KEY = 'whiteboard-directory-groups-expanded'

/** The expand keys of the open groups; no key means every group is collapsed (spec-00010-AC-6.5). */
export function readExpandedGroups(): string[] {
  const stored = localStorage.getItem(EXPANDED_KEY)
  return stored === null ? [] : (JSON.parse(stored) as string[])
}

export function writeExpandedGroups(keys: string[]): void {
  localStorage.setItem(EXPANDED_KEY, JSON.stringify(keys))
}

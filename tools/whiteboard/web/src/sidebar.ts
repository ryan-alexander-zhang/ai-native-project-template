/**
 * The navigation sidebar's two preferences, in the same local layer the theme
 * and the notification switch live in (design-00002 §17.1). They are view state,
 * but they survive a reload: they are the user's preference about the interface,
 * not their place in a document. Nothing here reaches `docs/`.
 */

const OPEN_KEY = 'whiteboard-sidebar'
const COLLAPSED_KEY = 'whiteboard-sidebar-collapsed'

/** No key means open: the sidebar is there until the user puts it away (spec-00008-AC-5.1). */
export function readSidebarOpen(): boolean {
  return localStorage.getItem(OPEN_KEY) !== 'closed'
}

export function writeSidebarOpen(open: boolean): void {
  localStorage.setItem(OPEN_KEY, open ? 'open' : 'closed')
}

/** The keys of the collapsed groups; no key means every group is expanded (spec-00008-AC-4.3). */
export function readCollapsed(): string[] {
  const stored = localStorage.getItem(COLLAPSED_KEY)
  return stored === null ? [] : (JSON.parse(stored) as string[])
}

export function writeCollapsed(keys: string[]): void {
  localStorage.setItem(COLLAPSED_KEY, JSON.stringify(keys))
}

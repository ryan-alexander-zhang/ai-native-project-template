import { CircleHelp, Keyboard, type LucideIcon, TerminalIcon } from 'lucide-react'
import type { SessionListing } from './api.ts'
import { Badge } from '@/components/ui/badge'

/** What the one marker shows: which icon, and the word its accessible name leads with. */
export interface SessionMarkerState {
  /** «Running», «Awaiting input» or «Ask» — never colour alone (design-00002 §14). */
  state: string
  icon: LucideIcon
}

/**
 * The marker a directory group carries, aggregated over its members
 * (spec-00010-FR-5): any session of any member awaiting input makes it the
 * waiting state, and everything else is the running one — two states, with the
 * running icon read off `spec-00005-FR-9`'s single-document rule (a
 * terminal-form session in the group, or asks alone).
 *
 * This is deliberately not `NodeCard`'s `markerOf` selection: that picks one
 * session first (terminal before ask) and then reads whether it waits, while a
 * group asks whether *anybody* is waiting — which is what FR-5 wants visible at
 * a glance (spec-00010-AC-5.5, AC-5.12, design-00002 §19.2).
 */
export function groupMarker(sessions: SessionListing[]): SessionMarkerState | undefined {
  if (sessions.length === 0) return undefined
  if (sessions.some((one) => one.awaiting === true)) return { state: 'Awaiting input', icon: Keyboard }
  return { state: 'Running', icon: sessions.some((one) => one.kind !== 'ask') ? TerminalIcon : CircleHelp }
}

export interface SessionMarkerProps {
  /** The accessible name: the caller owns it, since only it knows what the marker is of. */
  label: string
  icon: LucideIcon
  onActivate: () => void
}

/**
 * Slot ⑥ (design-00002 §4): this document — or this directory group — has at
 * least one session running, and this is the way to it (spec-00003-FR-10). One
 * marker whatever the number of them: a count would say nothing worth the space
 * (design-00002 §14).
 *
 * Activating it is not selecting the node — the gesture is stopped here, on
 * click and on the Enter that fires it, the same convention the inline id jump
 * follows (spec-00001-FR-57): the pointer events go too, or React Flow would
 * drag the node under the press. On a group node that is also what keeps one
 * click from both firing the marker and toggling the group (design-00002 §19.2).
 */
export function SessionMarker({ label, icon: Icon, onActivate }: SessionMarkerProps) {
  return (
    <Badge variant="outline" className="px-1.5 py-0.5" asChild>
      <button
        type="button"
        aria-label={label}
        onPointerDown={(event) => event.stopPropagation()}
        onMouseDown={(event) => event.stopPropagation()}
        onKeyDown={(event) => {
          if (event.key === 'Enter' || event.key === ' ') event.stopPropagation()
        }}
        onClick={(event) => {
          event.stopPropagation()
          onActivate()
        }}
      >
        <Icon className="size-3.5" aria-hidden />
      </button>
    </Badge>
  )
}

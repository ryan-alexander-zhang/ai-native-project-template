import { Bell, BellOff } from 'lucide-react'
import { Button } from '@/components/ui/button'
import type { NotifyState } from './notify.ts'

/**
 * The three states of the desktop notification switch, each with a word of its
 * own: which one holds is read off the label and the icon, never off a colour
 * (design-00002 §3, §13). «needs permission» is the one that has to be there —
 * it says the browser took the permission back, rather than letting the switch
 * look as if the user had turned it off (spec-00004-AC-4.3).
 */
const STATES: Record<NotifyState, { label: string; Icon: typeof Bell }> = {
  off: { label: 'off', Icon: BellOff },
  inactive: { label: 'needs permission', Icon: BellOff },
  active: { label: 'on', Icon: Bell },
}

/** The top bar's switch: one click, and the only place a permission is asked for (spec-00004-FR-1). */
export function NotifySwitch({ state, onToggle }: { state: NotifyState; onToggle: () => void }) {
  const { label, Icon } = STATES[state]
  return (
    <Button
      variant="outline"
      size="sm"
      className="gap-2"
      aria-label={`Desktop notifications: ${label}`}
      onClick={onToggle}
    >
      <Icon className="size-4" aria-hidden />
      {label}
    </Button>
  )
}

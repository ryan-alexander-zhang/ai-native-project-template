import { CircleCheck, CircleDashed, CircleX } from 'lucide-react'
import type { Coverage } from '../../src/requirements.ts'

/**
 * The three coverage states, each an icon with an accessible name and its own
 * token — the state must not travel by colour alone (spec-00001-AC-32.6,
 * design-00002 §9). The panel and the sub-canvas read the same table, so an
 * item wears the same mark wherever it is shown (spec-00001-FR-35).
 */
export const COVERAGE: Record<Coverage, { Icon: typeof CircleCheck; label: string; token: string }> = {
  verified: { Icon: CircleCheck, label: 'verified', token: 'var(--coverage-verified)' },
  failing: { Icon: CircleX, label: 'failing', token: 'var(--coverage-failing)' },
  uncovered: { Icon: CircleDashed, label: 'uncovered', token: 'var(--coverage-uncovered)' },
}

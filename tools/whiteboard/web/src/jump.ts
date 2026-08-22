import { createContext } from 'react'

export interface JumpTarget {
  /** Every resolvable id → its owning document, off the graph payload (spec-00001-FR-57). */
  idOwners: Record<string, string>
  /** The board's focus path — the same one the palette and the relation list take. */
  onJump: (docId: string) => void
}

/**
 * How the inline-id jump reaches the sub-canvas nodes: they are React Flow
 * node components, rendered from node `data` rather than down a prop chain, so
 * the context carries what a prop cannot (design-00002 §9). The panels get the
 * same pair as plain props.
 */
export const JumpContext = createContext<JumpTarget | undefined>(undefined)

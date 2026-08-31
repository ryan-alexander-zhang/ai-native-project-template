import { LoaderCircle, NotebookPen } from 'lucide-react'
import { useState } from 'react'
import type { CowriteMaterials } from '../../src/cowrite.ts'
import { MaterialsInput, useMaterialsDraft } from './MaterialsInput.tsx'
import { Button } from '@/components/ui/button'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'

export interface CowriteEntryProps {
  /**
   * The agents a cowrite may run under: every one the config declares. A cowrite
   * is an interactive terminal session, so the candidate set is not narrowed the
   * way an ask's is (spec-00006-FR-1 with spec-00001-FR-55).
   */
  agents: string[]
  /** Whether the board has a document of this id — what a material line is checked against. */
  known: (id: string) => boolean
  /**
   * Start the session. What comes back says whether it went: a refusal — an
   * illegal status, the cap, a network that dropped it — keeps this input open
   * with the materials intact (spec-00006-FR-9, design-00002 §15).
   */
  onSubmit: (materials: CowriteMaterials | undefined, agent?: string) => Promise<boolean>
  /** Whether starting anything on this document is out of the question just now. */
  disabled?: boolean
}

/**
 * The floating toolbar's cowrite entry and the launch input it opens
 * (design-00002 §15). The entry is **not** conditioned on the status: an illegal
 * target still shows it and the refusal comes at the submit, which is the
 * precedent clarify set (spec-00001-FR-9, spec-00006-AC-9.1).
 *
 * Empty materials are submittable — the opposite of the ask input's empty
 * question, because materials are an offer and a question is the whole request
 * (spec-00006-AC-3.3).
 */
export function CowriteEntry({ agents, known, onSubmit, disabled = false }: CowriteEntryProps) {
  const [open, setOpen] = useState(false)
  /** A launch already on its way, so a second press cannot start a second session. */
  const [sending, setSending] = useState(false)
  const draft = useMaterialsDraft(agents, known)
  const blocked = draft.unusable.length > 0

  /**
   * The materials are let go of only once the session is actually away: closing
   * first would throw them away on every refusal, and a refusal is exactly when
   * they are worth keeping.
   */
  async function submit() {
    if (blocked || sending) return
    setSending(true)
    try {
      if (!(await onSubmit(draft.materials, draft.agent))) return
      setOpen(false)
      draft.clear()
    } finally {
      setSending(false)
    }
  }

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button variant="ghost" size="sm" aria-label="Co-write" disabled={disabled}>
          <NotebookPen className="size-4" aria-hidden />
          Co-write
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" className="w-96">
        <MaterialsInput {...draft.fields}>
          <Button size="sm" className="ml-auto" onClick={submit} disabled={blocked || sending}>
            {sending ? (
              <LoaderCircle className="size-4 animate-spin" aria-hidden />
            ) : (
              <NotebookPen className="size-4" aria-hidden />
            )}
            Start co-writing
          </Button>
        </MaterialsInput>
      </PopoverContent>
    </Popover>
  )
}

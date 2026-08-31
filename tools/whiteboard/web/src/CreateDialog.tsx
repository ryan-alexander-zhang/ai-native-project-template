import { ChevronDown, FilePlus, LoaderCircle, NotebookPen } from 'lucide-react'
import { useEffect, useState } from 'react'
import type { CowriteMaterials } from '../../src/cowrite.ts'
import { MaterialsInput, useMaterialsDraft } from './MaterialsInput.tsx'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { isSlug } from './frontMatter.ts'

/**
 * The two ways a document is started (design-00002 §15): the blank buffer this
 * dialog always opened, and a cowrite session on a document filed for it.
 */
export type CreateMode = 'blank' | 'cowrite'

export interface CreateDialogProps {
  /** The types a document may be created at — the config's `entry`, nothing else (spec-00001-FR-53). */
  types: string[]
  /** The agents a cowrite may run under (spec-00001-FR-55); one is not a choice. */
  agents: string[]
  /** Whether the board has a document of this id — what a material line is checked against. */
  knownDoc: (id: string) => boolean
  open: boolean
  onOpenChange: (open: boolean) => void
  onCreate: (type: string, slug: string) => void
  /**
   * File the document and start co-writing it (spec-00006-FR-2). What comes back
   * says whether it went: a refusal leaves this dialog open with everything that
   * was typed still in it (design-00002 §15).
   */
  onCowrite: (type: string, slug: string, materials: CowriteMaterials | undefined, agent?: string) => Promise<boolean>
}

/**
 * The new-document dialog of spec-00001-FR-53, with the mode choice
 * spec-00006-FR-2 adds. It asks the two things only the user knows — which entry
 * type, and what to call it — and nothing else: the number comes from the server
 * (rule-00001-BR-18) and the body from the type's template.
 *
 * In the blank mode nothing is written here: confirming opens the prefilled
 * buffer and the file is created when that buffer is saved, which is what makes
 * an abandoned dialog cost nothing. In the cowrite mode confirming is the whole
 * act — the server files the document and starts the session, all or nothing
 * (design-00001 §11.2).
 */
export function CreateDialog({ types, agents, knownDoc, open, onOpenChange, onCreate, onCowrite }: CreateDialogProps) {
  const [type, setType] = useState(types[0] ?? '')
  const [slug, setSlug] = useState('')
  const [mode, setMode] = useState<CreateMode>('blank')
  /** A launch already on its way, so a second press cannot file a second document. */
  const [sending, setSending] = useState(false)
  const draft = useMaterialsDraft(agents, knownDoc)

  // Reopening starts over: the previous slug belonged to the document that was
  // — or was not — created last time, and so did its materials.
  useEffect(() => {
    if (!open) return
    setType(types[0] ?? '')
    setSlug('')
    setMode('blank')
    draft.clear()
    // The draft is rebuilt each render; clearing it is what the effect is for,
    // not something it should be re-run by.
  }, [open, types])

  // The slug is refused here as well as on the server (spec-00001-AC-53.4): the
  // reason is said next to the field being typed in, not in a toast after a
  // round trip. An empty field is not yet wrong, so it says nothing.
  const invalid = slug !== '' && !isSlug(slug)
  // A material line the board cannot use blocks the confirm the same way it
  // blocks the toolbar's launch (spec-00006-FR-3, design-00002 §15).
  const blocked = type === '' || slug === '' || invalid || sending || (mode === 'cowrite' && draft.unusable.length > 0)

  async function confirm() {
    if (blocked) return
    if (mode === 'blank') {
      onOpenChange(false)
      onCreate(type, slug)
      return
    }
    setSending(true)
    try {
      if (await onCowrite(type, slug, draft.materials, draft.agent)) onOpenChange(false)
    } finally {
      setSending(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>New document</DialogTitle>
          <DialogDescription>
            A flow entry document. The number is allocated for you; a blank one opens prefilled from the type's
            template and is created when you save it, and a co-written one is filed at once and written with an
            agent.
          </DialogDescription>
        </DialogHeader>

        <div className="flex flex-col gap-3">
          <Tabs value={mode} onValueChange={(value) => setMode(value as CreateMode)}>
            <TabsList className="h-7">
              <TabsTrigger value="blank" className="text-xs">
                <FilePlus className="size-3.5" aria-hidden />
                Blank
              </TabsTrigger>
              <TabsTrigger value="cowrite" className="text-xs">
                <NotebookPen className="size-3.5" aria-hidden />
                Co-write
              </TabsTrigger>
            </TabsList>
          </Tabs>

          <div className="flex items-center gap-2">
            <span className="text-muted-foreground w-12 text-xs">Type</span>
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="outline" size="sm" aria-label="Document type">
                  {type === '' ? 'pick a type' : type}
                  <ChevronDown className="size-3 opacity-60" aria-hidden />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="start">
                {types.map((candidate) => (
                  <DropdownMenuItem key={candidate} onSelect={() => setType(candidate)}>
                    {candidate}
                  </DropdownMenuItem>
                ))}
              </DropdownMenuContent>
            </DropdownMenu>
          </div>

          <div className="flex items-center gap-2">
            <label className="text-muted-foreground w-12 text-xs" htmlFor="create-slug">
              Slug
            </label>
            <input
              id="create-slug"
              value={slug}
              onChange={(event) => setSlug(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter') void confirm()
              }}
              placeholder="what-this-document-is-about"
              aria-invalid={invalid}
              className="border-input focus-visible:ring-ring/50 aria-invalid:border-destructive h-9 flex-1 rounded-md border bg-transparent px-3 font-mono text-sm outline-none focus-visible:ring-[3px]"
            />
          </div>

          {invalid ? (
            <p className="text-destructive text-xs">a slug is lowercase words joined by hyphens</p>
          ) : null}

          {/* The same materials the toolbar's launch takes, in the same shape and
              under the same discipline (design-00002 §15). */}
          {mode === 'cowrite' ? <MaterialsInput {...draft.fields} /> : null}
        </div>

        <DialogFooter>
          <Button onClick={confirm} disabled={blocked}>
            {sending ? (
              <LoaderCircle className="size-4 animate-spin" aria-hidden />
            ) : mode === 'cowrite' ? (
              <NotebookPen className="size-4" aria-hidden />
            ) : (
              <FilePlus className="size-4" aria-hidden />
            )}
            Create
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

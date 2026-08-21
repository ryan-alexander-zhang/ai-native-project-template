import { ChevronDown, FilePlus } from 'lucide-react'
import { useEffect, useState } from 'react'
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
import { isSlug } from './frontMatter.ts'

export interface CreateDialogProps {
  /** The types a document may be created at — the config's `entry`, nothing else (spec-00001-FR-53). */
  types: string[]
  open: boolean
  onOpenChange: (open: boolean) => void
  onCreate: (type: string, slug: string) => void
}

/**
 * The new-document dialog of spec-00001-FR-53. It asks the two things only the
 * user knows — which entry type, and what to call it — and nothing else: the
 * number comes from the server (rule-00001-BR-18) and the body from the type's
 * template.
 *
 * Nothing is written here. Confirming opens the prefilled buffer; the file is
 * created when that buffer is saved, which is what makes an abandoned dialog
 * cost nothing.
 */
export function CreateDialog({ types, open, onOpenChange, onCreate }: CreateDialogProps) {
  const [type, setType] = useState(types[0] ?? '')
  const [slug, setSlug] = useState('')

  // Reopening starts over: the previous slug belonged to the document that was
  // — or was not — created last time.
  useEffect(() => {
    if (!open) return
    setType(types[0] ?? '')
    setSlug('')
  }, [open, types])

  // The slug is refused here as well as on the server (spec-00001-AC-53.4): the
  // reason is said next to the field being typed in, not in a toast after a
  // round trip. An empty field is not yet wrong, so it says nothing.
  const invalid = slug !== '' && !isSlug(slug)

  function confirm() {
    if (type === '' || slug === '' || invalid) return
    onOpenChange(false)
    onCreate(type, slug)
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>New document</DialogTitle>
          <DialogDescription>
            A flow entry document. The number is allocated for you; it opens prefilled from the type's template
            and is created when you save it.
          </DialogDescription>
        </DialogHeader>

        <div className="flex flex-col gap-3">
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
                if (event.key === 'Enter') confirm()
              }}
              placeholder="what-this-document-is-about"
              aria-invalid={invalid}
              className="border-input focus-visible:ring-ring/50 aria-invalid:border-destructive h-9 flex-1 rounded-md border bg-transparent px-3 font-mono text-sm outline-none focus-visible:ring-[3px]"
            />
          </div>

          {invalid ? (
            <p className="text-destructive text-xs">a slug is lowercase words joined by hyphens</p>
          ) : null}
        </div>

        <DialogFooter>
          <Button onClick={confirm} disabled={type === '' || slug === '' || invalid}>
            <FilePlus className="size-4" aria-hidden />
            Create
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

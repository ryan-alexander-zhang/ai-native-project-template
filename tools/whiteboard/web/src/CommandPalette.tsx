import { useEffect, useState } from 'react'
import type { DocNode } from '../../src/docRepository.ts'
import { matchDocuments } from './canvasModel.ts'
import {
  Command,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
} from '@/components/ui/command'
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '@/components/ui/dialog'
import { typeIcon } from './status.ts'

export interface CommandPaletteProps {
  nodes: DocNode[]
  open: boolean
  onOpenChange: (open: boolean) => void
  onPick: (id: string) => void
}

/** spec-00001-FR-26 and FR-27: find a document by id or title, then go to it. */
export function CommandPalette({ nodes, open, onOpenChange, onPick }: CommandPaletteProps) {
  const [query, setQuery] = useState('')
  const matches = matchDocuments(nodes, query)

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'k' && (event.metaKey || event.ctrlKey)) {
        event.preventDefault()
        onOpenChange(!open)
      }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [open, onOpenChange])

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="overflow-hidden p-0" showCloseButton={false}>
        <DialogTitle className="sr-only">Find a document</DialogTitle>
        <DialogDescription className="sr-only">Search by id or title</DialogDescription>
        {/* Our own case-insensitive substring filter (FR-26), not cmdk's fuzzy scoring. */}
        <Command shouldFilter={false} label="Find a document">
          <CommandInput placeholder="Find a document by id or title" value={query} onValueChange={setQuery} />
          <CommandList>
            {matches.length === 0 ? <CommandEmpty>no match</CommandEmpty> : null}
            <CommandGroup>
              {matches.map((node) => {
                const Icon = typeIcon(node.type)
                return (
                  <CommandItem key={node.id} value={node.id} onSelect={() => onPick(node.id)}>
                    <Icon className="size-4 shrink-0 opacity-70" aria-hidden />
                    <span className="truncate">{node.title}</span>
                    <span className="text-muted-foreground ml-auto truncate font-mono text-xs">{node.id}</span>
                  </CommandItem>
                )
              })}
            </CommandGroup>
          </CommandList>
        </Command>
      </DialogContent>
    </Dialog>
  )
}

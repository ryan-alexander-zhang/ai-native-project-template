import { Bot, ChevronDown } from 'lucide-react'
import { type ReactNode, useState } from 'react'
import { type UnusableLine, readMaterials } from './cowriteMaterials.ts'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'

const FIELD =
  'border-input focus-visible:ring-ring/50 aria-invalid:border-destructive min-h-20 rounded-md border bg-transparent px-3 py-2 text-sm outline-none focus-visible:ring-[3px]'

/** What the two inputs and the agent picker are showing; the draft below holds it. */
export interface MaterialsFields {
  text: string
  onText: (value: string) => void
  references: string
  onReferences: (value: string) => void
  unusable: UnusableLine[]
  agents: string[]
  agent?: string
  onAgent: (name: string) => void
}

/**
 * The materials of one launch, held by the caller rather than by the inputs: a
 * refused launch keeps every word that was typed, which is the ask input's
 * discipline (design-00002 §14, §15). `materials` is what goes on the wire and
 * `unusable` is what blocks the send.
 */
export function useMaterialsDraft(agents: string[], known: (id: string) => boolean) {
  const [text, setText] = useState('')
  const [references, setReferences] = useState('')
  const [agent, setAgent] = useState<string>()
  const { materials, unusable } = readMaterials(text, references, known)
  // One agent is no choice at all: nothing is drawn and nothing is sent, so the
  // server takes the first (spec-00001-AC-55.4).
  const chosen = agent ?? (agents.length > 1 ? agents[0] : undefined)
  return {
    materials,
    unusable,
    agent: chosen,
    clear: () => {
      setText('')
      setReferences('')
    },
    fields: {
      text,
      onText: setText,
      references,
      onReferences: setReferences,
      unusable,
      agents,
      agent: chosen,
      onAgent: setAgent,
    } satisfies MaterialsFields,
  }
}

/**
 * The two multiline inputs of design-00002 §15 — the pasted text in one, the
 * documents, paths and URLs in the other, one per line — with the agent picker
 * and whatever action button the caller puts beside it. The lines the board
 * cannot use are named under the box they were typed in: never dropped, never
 * folded into the pasted text (spec-00006-FR-3).
 */
export function MaterialsInput({ children, ...fields }: MaterialsFields & { children?: ReactNode }) {
  const { text, onText, references, onReferences, unusable, agents, agent, onAgent } = fields
  return (
    <div className="flex flex-col gap-2">
      <span className="text-muted-foreground text-xs">Material</span>
      <textarea
        aria-label="Pasted material"
        value={text}
        onChange={(event) => onText(event.target.value)}
        placeholder="paste anything the agent should read"
        rows={4}
        className={FIELD}
      />
      <span className="text-muted-foreground text-xs">Documents, paths and URLs, one per line</span>
      <textarea
        aria-label="Material references"
        value={references}
        onChange={(event) => onReferences(event.target.value)}
        placeholder={'spec-00001-whiteboard\n/Users/me/notes.md\nhttps://example.com/page'}
        rows={3}
        aria-invalid={unusable.length > 0}
        className={FIELD}
      />
      {unusable.length === 0 ? null : (
        <ul aria-label="Unusable materials" className="text-destructive flex flex-col gap-0.5 text-xs">
          {/* Keyed by position as well as by text: the same unusable line typed
              twice is two entries, and one key for both drops one of them. */}
          {unusable.map((one, index) => (
            <li key={`${index}-${one.line}`}>
              <span className="font-mono">{one.line}</span> — {one.reason}
            </li>
          ))}
        </ul>
      )}
      <div className="flex items-center gap-2">
        {agents.length > 1 ? (
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="ghost" size="sm" aria-label="Co-write agent">
                <Bot className="size-4" aria-hidden />
                {agent}
                <ChevronDown className="size-3 opacity-60" aria-hidden />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start">
              {agents.map((name) => (
                <DropdownMenuItem key={name} onSelect={() => onAgent(name)}>
                  {name}
                </DropdownMenuItem>
              ))}
            </DropdownMenuContent>
          </DropdownMenu>
        ) : null}
        {children}
      </div>
    </div>
  )
}

import { Bot, ChevronDown, CircleHelp, LoaderCircle, Send } from 'lucide-react'
import { useState } from 'react'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'

export interface AskEntryProps {
  /**
   * The agents a question may be put to: the ones declaring a headless form and
   * no others (spec-00005-FR-2, AC-2.3). None of them declaring one is not this
   * component's case — the entry is then not drawn at all (AC-7.4).
   */
  agents: string[]
  /**
   * Put the question. What comes back says whether it went: a refusal — the cap,
   * a thread already busy, a network that dropped it — keeps this input open with
   * the draft intact, since the words are the user's and nothing else holds them
   * (spec-00005-FR-7's refusals as the interface takes them).
   */
  onSubmit: (question: string, agent?: string) => Promise<boolean>
  /**
   * Whether asking is out of the question just now: the session cap counts asks
   * like any other kind (spec-00003-FR-3), so at the cap the entry is locked with
   * its reason like every other starting point. A running session on this
   * document is **not** such a reason (spec-00005-FR-6).
   */
  disabled?: boolean
}

/**
 * The one question input, opened from either entry (design-00002 §14): the
 * node's floating toolbar keeps the place its ask entry always had, and the
 * editor's header has one of the same shape. Both open this, and asking is over
 * once the question is away — the input puts itself away and no terminal comes
 * up (spec-00005-FR-1, FR-3).
 */
export function AskEntry({ agents, onSubmit, disabled = false }: AskEntryProps) {
  const [open, setOpen] = useState(false)
  const [question, setQuestion] = useState('')
  const [agent, setAgent] = useState<string>()
  /** A question already on its way, so a second press cannot open a second thread. */
  const [sending, setSending] = useState(false)
  // One agent is no choice at all: nothing is drawn and nothing is sent, so the
  // server takes the first that declares a headless form (spec-00001-AC-55.4's
  // reading, narrowed by spec-00005-FR-2).
  const chosen = agent ?? (agents.length > 1 ? agents[0] : undefined)
  // An empty question is nothing to ask, and it is refused here rather than sent
  // for the server's 422 to bounce back (design-00002 §14).
  const empty = question.trim() === ''

  /**
   * The draft is let go of only once the question is actually away: closing
   * first would throw the user's words away on every refusal, and a refusal is
   * exactly the moment they are worth keeping.
   */
  async function submit() {
    if (empty || sending) return
    setSending(true)
    try {
      if (!(await onSubmit(question, chosen))) return
      setOpen(false)
      setQuestion('')
    } finally {
      setSending(false)
    }
  }

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button variant="ghost" size="sm" disabled={disabled}>
          <CircleHelp className="size-4" aria-hidden />
          Ask
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" className="w-96">
        <div className="flex flex-col gap-2">
          <textarea
            aria-label="Question"
            value={question}
            onChange={(event) => setQuestion(event.target.value)}
            placeholder="what would you like to know about this document?"
            rows={4}
            className="border-input focus-visible:ring-ring/50 min-h-20 rounded-md border bg-transparent px-3 py-2 text-sm outline-none focus-visible:ring-[3px]"
          />
          <div className="flex items-center gap-2">
            {agents.length > 1 ? (
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <Button variant="ghost" size="sm" aria-label="Ask agent">
                    <Bot className="size-4" aria-hidden />
                    {chosen}
                    <ChevronDown className="size-3 opacity-60" aria-hidden />
                  </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="start">
                  {agents.map((name) => (
                    <DropdownMenuItem key={name} onSelect={() => setAgent(name)}>
                      {name}
                    </DropdownMenuItem>
                  ))}
                </DropdownMenuContent>
              </DropdownMenu>
            ) : null}
            <Button size="sm" className="ml-auto" onClick={submit} disabled={empty || sending}>
              {sending ? (
                <LoaderCircle className="size-4 animate-spin" aria-hidden />
              ) : (
                <Send className="size-4" aria-hidden />
              )}
              Send
            </Button>
          </div>
        </div>
      </PopoverContent>
    </Popover>
  )
}

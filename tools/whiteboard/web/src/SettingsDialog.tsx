import { Eye, EyeOff, Plus, Settings, Trash2, Undo2, X } from 'lucide-react'
import { type ReactNode, useEffect, useRef, useState } from 'react'
import { toast } from 'sonner'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Switch } from '@/components/ui/switch'
import {
  type AgentSettingsError,
  type AgentSettingsView,
  type AgentSource,
  ApiError,
  type EffectiveAgent,
  type HeadlessDecl,
  type LocalAgentSettings,
  api,
} from './api.ts'
import {
  type AgentCard,
  type FieldValue,
  type OverridableKey,
  agentCards,
  commandSummary,
  cwdOf,
  fieldAt,
  withDefault,
  withDisabled,
  withEntry,
  withField,
  withHeadless,
  withoutEntry,
  withoutHeadless,
  withoutOverride,
} from './settingsModel.ts'

/** Where the local layer is written, named in a save's own failure (design-00001 §13.1). */
const LOCAL_FILE = '.whiteboard/agents.json'

/** The three sources, each a word rather than a colour alone (design-00002 §18.2, §6). */
const SOURCE: Record<AgentSource, { label: string; variant: 'secondary' | 'default' | 'outline' }> = {
  project: { label: 'project', variant: 'secondary' },
  local: { label: 'local', variant: 'default' },
  overridden: { label: 'project + local override', variant: 'outline' },
}

export interface SettingsDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  /**
   * The list a save just made effective (spec-00009-FR-8). The page that saved
   * shows it at once; no other page is told, which is the boundary
   * design-00001 §13.3 draws.
   */
  onSaved: (effective: EffectiveAgent[]) => void
}

/**
 * The settings panel of spec-00009-FR-7: both agent layers listed, and the local
 * one edited. A full-screen dialog like the command palette — settings are not
 * an act upon a document, so they take no side slot and are gone when they are
 * done with (design-00002 §18.1).
 *
 * Nothing is validated here. The merge rules live once, on the server
 * (design-00001 §13.2); a second copy in the form would be a second thing to
 * keep true, so a save that will not do comes back as a refusal and is shown
 * where it belongs (design-00002 §18.3).
 */
export function SettingsDialog({ open, onOpenChange, onSaved }: SettingsDialogProps) {
  const [view, setView] = useState<AgentSettingsView>()
  /** The local layer being edited; a save PUTs this whole (design-00001 §13.3). */
  const [local, setLocal] = useState<LocalAgentSettings>({})
  const [dirty, setDirty] = useState(false)
  const [expanded, setExpanded] = useState<string>()
  /** Which env values are in the clear, one by one and never together (spec-00009-AC-7.8). */
  const [shown, setShown] = useState<string[]>([])
  const [refusal, setRefusal] = useState<AgentSettingsError>()
  const [saving, setSaving] = useState(false)
  /** The name being typed for a new local entry; nothing means the field is away. */
  const [naming, setNaming] = useState<string>()

  // Read on every open (design-00002 §18.1): a local file edited by hand between
  // two opens shows its error at the second one (spec-00009-AC-4.4). Nothing is
  // polled while it is open, and a save answers with what a re-read would say.
  useEffect(() => {
    if (!open) return
    let live = true
    setView(undefined)
    setExpanded(undefined)
    setShown([])
    setRefusal(undefined)
    setNaming(undefined)
    setDirty(false)
    void (async () => {
      try {
        const read = await api.agentSettings()
        if (!live) return
        setView(read)
        setLocal(read.local ?? {})
      } catch (error) {
        toast.error(error instanceof Error ? error.message : String(error))
      }
    })()
    return () => {
      live = false
    }
  }, [open])

  /** Every edit goes through here, so «there is something to save» needs no comparison. */
  function edit(next: LocalAgentSettings) {
    setLocal(next)
    setDirty(true)
  }

  const cards = view === undefined ? [] : agentCards(view.project, local, view.effective.map((one) => one.name))
  // A refusal that names a key of an entry on show belongs under that field;
  // one that names anything else belongs at the top (design-00002 §18.3).
  const at = fieldAt(refusal?.at)
  const fieldError =
    at !== undefined && refusal !== undefined && cards.some((card) => card.name === at.name)
      ? { ...at, message: refusal.message }
      : undefined
  const top = fieldError === undefined ? (refusal ?? view?.error) : undefined

  async function save() {
    if (view === undefined) return
    setSaving(true)
    try {
      const saved = await api.saveAgentSettings(local)
      // The response is the re-read: the list it names is already the one the
      // next admission will use, so nothing is fetched again (design-00001 §13.3).
      setView({ ...view, local, effective: saved.effective, error: undefined, notices: saved.notices })
      setRefusal(undefined)
      setDirty(false)
      onSaved(saved.effective)
    } catch (error) {
      const said = refusalOf(error)
      setRefusal(said)
      const target = fieldAt(said.at)
      if (target !== undefined) setExpanded(target.name)
    } finally {
      setSaving(false)
    }
  }

  function add(name: string) {
    edit(withEntry(local, name))
    setExpanded(name)
    setNaming(undefined)
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-3xl">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <Settings className="size-4" aria-hidden />
            Agent settings
          </DialogTitle>
          <DialogDescription>
            Both layers: the agents this repo declares, and what this machine alone says about them. Saving takes
            effect on the next session started, and leaves running ones alone.
          </DialogDescription>
        </DialogHeader>

        <div className="flex items-center gap-2">
          {naming === undefined ? (
            <Button variant="outline" size="sm" className="gap-2" onClick={() => setNaming('')}>
              <Plus className="size-4" aria-hidden />
              New local agent
            </Button>
          ) : (
            <>
              <Input
                aria-label="New agent name"
                value={naming}
                onChange={(event) => setNaming(event.target.value)}
                className="h-8 w-56 font-mono text-xs"
              />
              <Button size="sm" disabled={naming.trim() === ''} onClick={() => add(naming.trim())}>
                Add
              </Button>
              <Button variant="ghost" size="icon-sm" aria-label="Cancel the new agent" onClick={() => setNaming(undefined)}>
                <X aria-hidden />
              </Button>
            </>
          )}
          {/* Nothing changed is nothing to write, so the save is not offered
              (design-00002 §18.2). */}
          <Button size="sm" className="ml-auto" disabled={!dirty || saving} onClick={() => void save()}>
            Save
          </Button>
        </div>

        {top === undefined ? null : (
          <p className="text-destructive text-xs">
            {top.message}
            {top.at === undefined ? null : <span className="font-mono"> ({top.at})</span>}
          </p>
        )}
        {(view?.notices ?? []).map((notice) => (
          <p key={`${notice.name}-${notice.message}`} className="text-muted-foreground text-xs">
            <span className="font-mono">{notice.name}</span> — {notice.message}
          </p>
        ))}

        {view === undefined ? (
          <p className="text-muted-foreground text-xs">reading the agent settings…</p>
        ) : (
          <ul aria-label="Agents" className="max-h-[60vh] space-y-2 overflow-y-auto">
            {cards.map((card) => (
              <li
                key={card.name}
                data-testid={`agent-${card.name}`}
                className={card.disabled ? 'rounded-md border p-2 opacity-60' : 'rounded-md border p-2'}
              >
                <Row
                  card={card}
                  captures={view.captures}
                  expanded={expanded === card.name}
                  shown={shown}
                  error={fieldError?.name === card.name ? fieldError : undefined}
                  onToggle={() => setExpanded((current) => (current === card.name ? undefined : card.name))}
                  onShow={(key) =>
                    setShown((current) =>
                      current.includes(key) ? current.filter((one) => one !== key) : [...current, key],
                    )
                  }
                  onField={(key, value) => edit(withField(local, card, key, value))}
                  onNoHeadless={(off, headless) =>
                    edit(off ? withoutHeadless(local, card) : withHeadless(local, card, headless))
                  }
                  onUndo={(key) => edit(withoutOverride(local, card.name, key))}
                  onDisabled={(off) => edit(withDisabled(local, card.name, off))}
                  onDefault={() => edit(withDefault(local, card.name))}
                  onDelete={() => {
                    edit(withoutEntry(local, card.name))
                    setExpanded(undefined)
                  }}
                />
              </li>
            ))}
          </ul>
        )}
      </DialogContent>
    </Dialog>
  )
}

/** A save's refusal as the panel words it; a write that failed names the file it could not write. */
function refusalOf(error: unknown): AgentSettingsError {
  if (!(error instanceof ApiError)) {
    return { message: error instanceof Error ? error.message : String(error) }
  }
  if (error.status === 500) return { message: `could not write ${LOCAL_FILE} — ${error.message}` }
  return { message: error.message, at: error.at }
}

interface RowProps {
  card: AgentCard
  captures: string[]
  expanded: boolean
  shown: string[]
  error?: { key: string; message: string }
  onToggle: () => void
  onShow: (key: string) => void
  onField: <K extends OverridableKey>(key: K, value: FieldValue[K]) => void
  onNoHeadless: (off: boolean, headless: HeadlessDecl) => void
  onUndo: (key: OverridableKey) => void
  onDisabled: (off: boolean) => void
  onDefault: () => void
  onDelete: () => void
}

/**
 * One card of the list (design-00002 §18.2): the name and its badges, the command
 * that would actually run, and the entry's model and env keys — the values
 * masked, so opening the panel over a shared screen gives no secret away
 * (spec-00009-AC-7.7).
 */
function Row(props: RowProps) {
  const { card, expanded, shown, onToggle, onShow } = props
  const env = Object.entries(card.entry.env ?? {})
  return (
    <>
      {/* A real button, reached by Tab and fired by Enter (design-00002 §18.5). */}
      <button
        type="button"
        aria-expanded={expanded}
        onClick={onToggle}
        className="flex w-full flex-wrap items-center gap-2 text-left text-xs"
      >
        <span className="font-mono font-medium">{card.name}</span>
        <Badge variant={SOURCE[card.source].variant}>{SOURCE[card.source].label}</Badge>
        {card.isDefault ? <Badge variant="secondary">default</Badge> : null}
        {card.entry.headless === undefined ? null : <Badge variant="outline">headless</Badge>}
        {card.disabled ? <Badge variant="outline">disabled</Badge> : null}
      </button>
      <p className="text-muted-foreground truncate font-mono text-xs">{commandSummary(card.entry)}</p>
      {card.entry.model === undefined && env.length === 0 ? null : (
        <p className="flex flex-wrap items-center gap-x-3 text-xs">
          {card.entry.model === undefined ? null : (
            <span className="font-mono">model: {card.entry.model}</span>
          )}
          {env.map(([key, value]) => (
            <span key={key} className="flex items-center gap-1 font-mono">
              {key}:
              <Masked
                value={value}
                shown={shown.includes(`${card.name}\n${key}`)}
                onToggle={() => onShow(`${card.name}\n${key}`)}
              />
            </span>
          ))}
        </p>
      )}
      {card.appended ? (
        <p className="text-muted-foreground text-xs">
          This CLI has not been checked against the write scope (design-00001 §11.5): a write outside docs/ is not
          stopped by the board, and that is yours to answer for.
        </p>
      ) : null}
      {expanded ? <Form {...props} /> : null}
    </>
  )
}

/**
 * One env value: masked while it is only being read, in the clear once it is
 * asked for, and each one on its own (spec-00009-AC-7.8). The button's pressed
 * state is what says which of the two it is (design-00002 §18.5).
 */
function Masked({ value, shown, onToggle }: { value: string; shown: boolean; onToggle: () => void }) {
  return (
    <>
      <span>{shown ? value : '••••••'}</span>
      <Button
        variant="ghost"
        size="icon-xs"
        aria-label={shown ? 'Hide value' : 'Show value'}
        aria-pressed={shown}
        onClick={onToggle}
      >
        {shown ? <EyeOff aria-hidden /> : <Eye aria-hidden />}
      </Button>
    </>
  )
}

/**
 * The editing form of design-00002 §18.3. Every key but `cwd` is the local
 * layer's to replace — the working directory is the first barrier of the write
 * scope, and an appended entry's is `docs` and nothing else (spec-00009-FR-3).
 */
function Form({ card, captures, error, onField, onNoHeadless, onUndo, onDisabled, onDefault, onDelete }: RowProps) {
  const headless: HeadlessDecl = card.entry.headless ?? { first: [], resume: [], capture: captures[0] ?? '' }
  const undo = (key: OverridableKey) =>
    card.overridden.includes(key) ? (
      <Button variant="ghost" size="icon-xs" aria-label={`Undo the local ${key}`} onClick={() => onUndo(key)}>
        <Undo2 aria-hidden />
      </Button>
    ) : null
  const said = (key: string) => (error?.key === key ? <Refusal message={error.message} /> : null)

  return (
    <div className="mt-2 space-y-3 border-t pt-2">
      <Field label="Command" undo={undo('command')}>
        <Input
          aria-label="Command"
          value={card.entry.command}
          onChange={(event) => onField('command', event.target.value)}
          className="h-8 font-mono text-xs"
        />
      </Field>
      {said('command')}

      <Field label="Model" undo={undo('model')}>
        <Input
          aria-label="Model"
          value={card.entry.model ?? ''}
          onChange={(event) => onField('model', event.target.value)}
          className="h-8 font-mono text-xs"
        />
      </Field>
      {said('model')}

      <Strings label="Arguments" values={card.entry.args} undo={undo('args')} onChange={(next) => onField('args', next)} />
      {said('args')}

      <Field label="Working directory">
        {/* Never editable: the local layer may not move an agent out of `docs`
            (decision-00017 §2 第 4 条), and an appended one is there by
            definition (spec-00009-AC-7.6). */}
        <span aria-label="Working directory" className="font-mono text-xs">
          {cwdOf(card)}
        </span>
      </Field>

      <Pairs
        env={card.entry.env ?? {}}
        undo={undo('env')}
        onChange={(next) => onField('env', next)}
      />
      {said('env')}

      <Strings
        label="Headless first"
        values={headless.first}
        undo={undo('headless')}
        onChange={(next) => onField('headless', { ...headless, first: next })}
      />
      <Strings
        label="Headless resume"
        values={headless.resume}
        onChange={(next) => onField('headless', { ...headless, resume: next })}
      />
      <Field label="Capture">
        <Select value={headless.capture} onValueChange={(next) => onField('headless', { ...headless, capture: next })}>
          <SelectTrigger size="sm" aria-label="Capture" className="w-56 font-mono text-xs">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {captures.map((one) => (
              <SelectItem key={one} value={one}>
                {one}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </Field>
      <Field label="No headless">
        {/* The entry declares no headless form, and so leaves the ask option set
            (design-00002 §18.3). Turning it back off gives a project entry its own
            declaration back by undoing the override, and writes an appended
            entry's form on show, which for one that has none is the blank one
            above (spec-00009-FR-7). */}
        <Switch
          aria-label="No headless"
          checked={card.entry.headless === undefined}
          onCheckedChange={(off) => onNoHeadless(off, headless)}
        />
      </Field>
      {said('headless')}

      <div className="flex items-center gap-4 text-xs">
        <label className="flex items-center gap-2">
          <Switch checked={card.disabled} onCheckedChange={onDisabled} />
          Disabled
        </label>
        <label className="flex items-center gap-2">
          {/* One default over the whole list, so the control is a radio and not a
              switch of its own (design-00002 §18.3). */}
          <input type="radio" name="agent-default" checked={card.isDefault} onChange={onDefault} />
          Default
        </label>
        {card.appended ? (
          // One step, no second asking: nothing is written until the save, and
          // closing the dialog is how the whole draft is let go (design-00002 §18.3).
          <Button
            variant="ghost"
            size="icon-sm"
            className="text-destructive ml-auto"
            aria-label={`Delete ${card.name}`}
            onClick={onDelete}
          >
            <Trash2 aria-hidden />
          </Button>
        ) : null}
      </div>
    </div>
  )
}

/** A labelled row of the form, with the undo of its local override beside it when it has one. */
function Field({ label, undo, children }: { label: string; undo?: ReactNode; children: ReactNode }) {
  return (
    <div className="flex items-center gap-2">
      <span className="text-muted-foreground w-36 shrink-0 text-xs">{label}</span>
      {children}
      {undo}
    </div>
  )
}

/** A list of argv elements: one input each, added and removed one at a time (design-00002 §18.3). */
function Strings({
  label,
  values,
  undo,
  onChange,
}: {
  label: string
  values: string[]
  undo?: ReactNode
  onChange: (next: string[]) => void
}) {
  return (
    <Field label={label} undo={undo}>
      <ul aria-label={label} className="flex flex-1 flex-col gap-1">
        {values.map((value, index) => (
          // Keyed by position: the elements are an ordered list of plain strings
          // and two of them may read the same.
          <li key={index} className="flex items-center gap-1">
            <Input
              aria-label={`${label} ${index + 1}`}
              value={value}
              onChange={(event) => onChange(values.map((one, at) => (at === index ? event.target.value : one)))}
              className="h-8 font-mono text-xs"
            />
            <Button
              variant="ghost"
              size="icon-sm"
              aria-label={`Remove ${label} ${index + 1}`}
              onClick={() => onChange(values.filter((_one, at) => at !== index))}
            >
              <X aria-hidden />
            </Button>
          </li>
        ))}
        <li>
          <Button variant="ghost" size="xs" onClick={() => onChange([...values, ''])}>
            <Plus aria-hidden />
            Add to {label.toLowerCase()}
          </Button>
        </li>
      </ul>
    </Field>
  )
}

/** The env mapping: key and value side by side, in the clear because this is the writing of them, not the reading (design-00002 §18.3). */
function Pairs({
  env,
  undo,
  onChange,
}: {
  env: Record<string, string>
  undo?: ReactNode
  onChange: (next: Record<string, string>) => void
}) {
  const pairs = Object.entries(env)
  const replace = (index: number, pair: [string, string]) =>
    onChange(Object.fromEntries(pairs.map((one, at) => (at === index ? pair : one))))
  return (
    <Field label="Environment" undo={undo}>
      <ul aria-label="Environment" className="flex flex-1 flex-col gap-1">
        {pairs.map(([key, value], index) => (
          <li key={index} className="flex items-center gap-1">
            <Input
              aria-label={`Environment key ${index + 1}`}
              value={key}
              onChange={(event) => replace(index, [event.target.value, value])}
              className="h-8 font-mono text-xs"
            />
            <Input
              aria-label={`Environment value ${index + 1}`}
              value={value}
              onChange={(event) => replace(index, [key, event.target.value])}
              className="h-8 font-mono text-xs"
            />
            <Button
              variant="ghost"
              size="icon-sm"
              aria-label={`Remove environment ${index + 1}`}
              onClick={() => onChange(Object.fromEntries(pairs.filter((_one, at) => at !== index)))}
            >
              <X aria-hidden />
            </Button>
          </li>
        ))}
        <li>
          <Button variant="ghost" size="xs" onClick={() => onChange({ ...env, '': '' })}>
            <Plus aria-hidden />
            Add an environment variable
          </Button>
        </li>
      </ul>
    </Field>
  )
}

/** A save's refusal under the field it is about, and scrolled to (design-00002 §18.3). */
function Refusal({ message }: { message: string }) {
  const at = useRef<HTMLParagraphElement>(null)
  useEffect(() => at.current?.scrollIntoView({ block: 'nearest' }), [message])
  return (
    <p ref={at} className="text-destructive ml-38 text-xs">
      {message}
    </p>
  )
}

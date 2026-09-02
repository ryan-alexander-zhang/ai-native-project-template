// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { toast } from 'sonner'
import type { DocGraph, DocNode } from '../../src/docRepository.ts'
import { Board } from '../src/Board.tsx'
import {
  type AgentEntry,
  type AgentSettingsView,
  ApiError,
  type ConfigPayload,
  type EffectiveAgent,
  type HeadlessDecl,
  api,
} from '../src/api.ts'

const HEADLESS: HeadlessDecl = {
  first: ['-p', '{question}'],
  resume: ['-p', '--resume', '{session}', '{question}'],
  capture: 'claude-json',
}

/** The project layer as the repo carries it: one agent that answers questions too. */
const CLAUDE: AgentEntry = {
  name: 'claude',
  command: 'claude',
  args: ['--model', '{model}'],
  cwd: 'docs',
  model: 'm1',
  headless: HEADLESS,
}
/** A second project entry with no headless form, so an ask has one choice and a session two. */
const OTHER: AgentEntry = { name: 'other', command: 'other', args: [], cwd: 'docs' }

const listed = (name: string, headless: boolean, source: EffectiveAgent['source'] = 'project'): EffectiveAgent => ({
  name,
  headless,
  source,
})

function node(overrides: Partial<DocNode> = {}): DocNode {
  return {
    id: 'prd-00001-x',
    path: 'prd/a.md',
    type: 'prd',
    status: 'draft',
    title: 'Whiteboard PRD',
    relations: {},
    ok: true,
    problems: [],
    ...overrides,
  }
}

/**
 * What the server is serving. `settings` is the settings panel's own payload
 * (`GET /api/settings/agents`) and `agents` is the effective list the config
 * hands the board, which is what the pickers are drawn off.
 */
function serve(settings: Partial<AgentSettingsView> = {}, agents: EffectiveAgent[] = [listed('claude', true)]) {
  const graph: DocGraph = { nodes: [node()], edges: [], issues: [], diagnostics: [], idOwners: {} }
  vi.spyOn(api, 'graph').mockResolvedValue(graph)
  vi.spyOn(api, 'transitions').mockResolvedValue(['active'])
  vi.spyOn(api, 'nextSteps').mockResolvedValue([])
  vi.spyOn(api, 'sessions').mockResolvedValue([])
  vi.spyOn(api, 'items').mockResolvedValue({ items: [], diagnostics: [] })
  vi.spyOn(api, 'doc').mockResolvedValue({ path: 'prd/a.md', content: '# X', hash: 'hash-1' })
  vi.spyOn(api, 'asks').mockResolvedValue([])
  const config: ConfigPayload = {
    types: { prd: 'living', spec: 'living' },
    relations: ['parent'],
    flow: {},
    focus: {},
    agents,
    entry: [],
    carries: {},
    maxSessions: 3,
    clarifiable: [],
    auditable: [],
  }
  vi.spyOn(api, 'config').mockResolvedValue(config)
  vi.spyOn(api, 'agentSettings').mockResolvedValue({
    project: [CLAUDE],
    local: null,
    effective: agents,
    captures: ['claude-json'],
    notices: [],
    ...settings,
  })
}

/** The board, with the one node selected so its floating toolbar is up. */
async function openBoard() {
  render(<Board />)
  await waitFor(() => expect(screen.getByTestId('node-prd-00001-x')).toBeTruthy())
  fireEvent.click(screen.getByTestId('node-prd-00001-x'))
  await waitFor(() => expect(screen.getByRole('button', { name: 'Edit' })).toBeTruthy())
}

/** The top-bar entry, and the panel it opens (spec-00009-FR-7). */
async function openSettings() {
  await userEvent.click(screen.getByRole('button', { name: 'Agent settings' }))
  await screen.findByRole('list', { name: 'Agents' })
}

async function openBoardAndSettings() {
  await openBoard()
  await openSettings()
}

/** Away with the dialog, so what the page shows behind it can be read again. */
async function closeSettings() {
  await userEvent.keyboard('{Escape}')
  await waitFor(() => expect(screen.queryByRole('list', { name: 'Agents' })).toBeNull())
}

const cards = () => Array.from(screen.getByRole('list', { name: 'Agents' }).children)
const card = (name: string) => screen.getByTestId(`agent-${name}`)

/** Open one card's form; its head is the first button of the card. */
async function expand(name: string) {
  await userEvent.click(within(card(name)).getAllByRole('button')[0]!)
}

const save = () => userEvent.click(screen.getByRole('button', { name: 'Save' }))

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('the settings panel', () => {
  // spec-00009-AC-7.1
  it('lists both layers, each entry with where it came from', async () => {
    serve(
      {
        local: {
          overrides: { claude: { model: 'm2' } },
          entries: { 'codex-local': { command: 'codex', args: [] } },
        },
      },
      [listed('claude', true, 'overridden'), listed('codex-local', false, 'local')],
    )
    await openBoardAndSettings()

    expect(cards()).toHaveLength(2)
    expect(within(card('claude')).getByText('project + local override')).toBeTruthy()
    expect(within(card('claude')).getByText('model: m2')).toBeTruthy()
    expect(within(card('codex-local')).getByText('local')).toBeTruthy()
    expect(within(card('codex-local')).getByText(/has not been checked against the write scope/)).toBeTruthy()
  })

  // spec-00009-AC-7.2
  it('puts a field back to the project value when its override is undone', async () => {
    serve({ local: { overrides: { claude: { model: 'm2' } } } }, [listed('claude', true, 'overridden')])
    const put = vi
      .spyOn(api, 'saveAgentSettings')
      .mockResolvedValue({ effective: [listed('claude', true)], notices: [] })
    await openBoardAndSettings()

    await expand('claude')
    await userEvent.click(screen.getByRole('button', { name: 'Undo the local model' }))
    await save()

    expect(put).toHaveBeenCalledWith({ overrides: {} })
    await waitFor(() => expect(within(card('claude')).getByText('model: m1')).toBeTruthy())
    expect(within(card('claude')).getByText('project')).toBeTruthy()
  })

  // spec-00009-AC-7.3 — every key but the working directory is the local layer's
  // to replace, and that one is the write-scope barrier (decision-00017 §2 第 4 条)
  it('leaves the working directory read-only and the other keys editable', async () => {
    serve({ project: [{ ...CLAUDE, env: { FOO: 'bar' } }] })
    await openBoardAndSettings()

    await expand('claude')

    const cwd = screen.getByLabelText('Working directory')
    expect(cwd.tagName).toBe('SPAN')
    expect(cwd.textContent).toBe('docs')
    for (const label of ['Command', 'Model', 'Arguments 1', 'Environment key 1', 'Headless first 1']) {
      const control = screen.getByLabelText(label)
      expect(control.tagName).toBe('INPUT')
      expect((control as HTMLInputElement).readOnly).toBe(false)
    }
  })

  // spec-00009-AC-7.4
  it('still lists a disabled entry, and lets it be enabled again', async () => {
    serve({ project: [CLAUDE, OTHER], local: { disabled: ['other'] } }, [listed('claude', true)])
    await openBoardAndSettings()

    expect(within(card('other')).getByText('disabled')).toBeTruthy()

    await expand('other')
    await userEvent.click(within(card('other')).getByRole('switch', { name: 'Disabled' }))

    expect(within(card('other')).queryByText('disabled')).toBeNull()
  })

  // spec-00009-AC-7.5
  it('lists the one project entry and still offers a local one to be added', async () => {
    serve()
    await openBoardAndSettings()

    expect(cards()).toHaveLength(1)
    expect(within(card('claude')).getByText('project')).toBeTruthy()
    expect(screen.getByRole('button', { name: /New local agent/ })).toBeTruthy()
  })

  // spec-00009-AC-7.6 — an added entry runs in `docs` and nowhere else
  it('shows an added entry as running in docs, uneditably', async () => {
    serve({ local: { entries: { 'codex-local': { command: 'codex' } } } }, [
      listed('claude', true),
      listed('codex-local', false, 'local'),
    ])
    await openBoardAndSettings()

    await expand('codex-local')

    const cwd = screen.getByLabelText('Working directory')
    expect(cwd.tagName).toBe('SPAN')
    expect(cwd.textContent).toBe('docs')
  })

  // spec-00009-AC-7.7 — opening the panel over a shared screen gives nothing away
  it('masks every env value', async () => {
    serve({ project: [{ ...CLAUDE, env: { FOO: 'bar', BAZ: 'qux' } }] })
    await openBoardAndSettings()

    expect(screen.getAllByText('••••••')).toHaveLength(2)
    expect(screen.queryByText('bar')).toBeNull()
    expect(screen.queryByText('qux')).toBeNull()
  })

  // spec-00009-AC-7.8 — one value at a time, and the others stay masked
  it('shows one env value in the clear when it is asked for', async () => {
    serve({ project: [{ ...CLAUDE, env: { FOO: 'bar', BAZ: 'qux' } }] })
    await openBoardAndSettings()

    await userEvent.click(screen.getAllByRole('button', { name: 'Show value' })[0]!)

    expect(screen.getByText('bar')).toBeTruthy()
    expect(screen.getAllByText('••••••')).toHaveLength(1)
    const shown = screen.getByRole('button', { name: 'Hide value' })
    expect(shown.getAttribute('aria-pressed')).toBe('true')
  })
})

/**
 * spec-00009-FR-8: the page that saved shows the new list at once — no reload,
 * and no other page told.
 */
describe('the list a save makes effective', () => {
  // spec-00009-AC-8.1
  it('draws the agent picker once a saved local entry makes the list two long', async () => {
    serve()
    const put = vi
      .spyOn(api, 'saveAgentSettings')
      .mockResolvedValue({ effective: [listed('claude', true), listed('codex-local', false, 'local')], notices: [] })
    await openBoard()
    expect(screen.queryByLabelText('Agent')).toBeNull()
    await openSettings()

    await userEvent.click(screen.getByRole('button', { name: /New local agent/ }))
    fireEvent.change(screen.getByLabelText('New agent name'), { target: { value: 'codex-local' } })
    await userEvent.click(screen.getByRole('button', { name: 'Add' }))
    fireEvent.change(screen.getByLabelText('Command'), { target: { value: 'codex' } })
    await save()
    await closeSettings()

    expect(put).toHaveBeenCalledWith({ entries: { 'codex-local': { command: 'codex' } } })
    await userEvent.click(screen.getByLabelText('Agent'))
    expect((await screen.findAllByRole('menuitem')).map((one) => one.textContent)).toEqual(['claude', 'codex-local'])
  })

  // spec-00009-AC-8.2
  it('takes the picker away once a saved deletion makes the list one long', async () => {
    serve({ local: { entries: { 'codex-local': { command: 'codex' } } } }, [
      listed('claude', true),
      listed('codex-local', false, 'local'),
    ])
    const put = vi
      .spyOn(api, 'saveAgentSettings')
      .mockResolvedValue({ effective: [listed('claude', true)], notices: [] })
    await openBoard()
    expect(screen.getByLabelText('Agent')).toBeTruthy()
    await openSettings()

    await expand('codex-local')
    await userEvent.click(screen.getByRole('button', { name: 'Delete codex-local' }))
    await save()
    await closeSettings()

    expect(put).toHaveBeenCalledWith({ entries: {} })
    expect(screen.queryByLabelText('Agent')).toBeNull()
  })

  // spec-00009-AC-8.3 — a headless declaration saved onto the second entry puts
  // it in the ask's choice, which was one long until then
  it('widens the ask choice once a saved entry declares a headless form', async () => {
    serve({ local: { entries: { 'codex-local': { command: 'codex' } } } }, [
      listed('claude', true),
      listed('codex-local', false, 'local'),
    ])
    vi.spyOn(api, 'saveAgentSettings').mockResolvedValue({
      effective: [listed('claude', true), listed('codex-local', true, 'local')],
      notices: [],
    })
    await openBoard()
    await openSettings()
    expect(screen.queryByLabelText('Ask agent')).toBeNull()

    await expand('codex-local')
    await userEvent.click(screen.getByRole('button', { name: 'Add to headless first' }))
    fireEvent.change(screen.getByLabelText('Headless first 1'), { target: { value: '-p' } })
    await userEvent.click(screen.getByRole('button', { name: 'Add to headless resume' }))
    fireEvent.change(screen.getByLabelText('Headless resume 1'), { target: { value: '--resume' } })
    await save()
    await closeSettings()

    await userEvent.click(screen.getByRole('button', { name: 'Ask' }))
    expect(await screen.findByLabelText('Ask agent')).toBeTruthy()
    await userEvent.click(screen.getByLabelText('Ask agent'))
    expect((await screen.findAllByRole('menuitem')).map((one) => one.textContent)).toEqual(['claude', 'codex-local'])
  })

  // spec-00009-AC-9.3's precondition, reachable from the panel: «no headless» on a
  // project entry is the one null the file admits, and it is what takes that entry
  // out of the ask choice (design-00001 §13.1, design-00002 §18.3)
  it('writes a null headless override when a project entry is told to declare none', async () => {
    serve({ project: [CLAUDE, OTHER] }, [listed('claude', true), listed('other', false)])
    const put = vi
      .spyOn(api, 'saveAgentSettings')
      .mockResolvedValue({ effective: [listed('claude', false, 'overridden'), listed('other', false)], notices: [] })
    await openBoard()
    await openSettings()

    await expand('claude')
    await userEvent.click(within(card('claude')).getByRole('switch', { name: 'No headless' }))
    expect(within(card('claude')).queryByText('headless')).toBeNull()
    await save()
    await closeSettings()

    expect(put).toHaveBeenCalledWith({ overrides: { claude: { headless: null } } })
    expect(screen.queryByRole('button', { name: 'Ask' })).toBeNull()
  })

  // spec-00009-FR-7 — the same switch on an added entry: nothing to take away, so
  // the key is simply not written (design-00002 §18.3)
  it('drops the headless key altogether when an added entry is told to declare none', async () => {
    serve({ local: { entries: { 'codex-local': { command: 'codex', headless: HEADLESS } } } }, [
      listed('claude', true),
      listed('codex-local', true, 'local'),
    ])
    const put = vi
      .spyOn(api, 'saveAgentSettings')
      .mockResolvedValue({ effective: [listed('claude', true), listed('codex-local', false, 'local')], notices: [] })
    await openBoardAndSettings()

    await expand('codex-local')
    await userEvent.click(within(card('codex-local')).getByRole('switch', { name: 'No headless' }))
    await save()

    expect(put).toHaveBeenCalledWith({ entries: { 'codex-local': { command: 'codex' } } })
  })

  // spec-00009-FR-7 — the same switch turned back off on a project entry undoes
  // the local override rather than writing a copy of what it took away: the
  // entry's own headless declaration is what comes back (design-00002 §18.3)
  it('gives a project entry its own headless declaration back when the switch is turned off again', async () => {
    serve({ project: [CLAUDE, OTHER] }, [listed('claude', true), listed('other', false)])
    const put = vi
      .spyOn(api, 'saveAgentSettings')
      .mockResolvedValue({ effective: [listed('claude', true), listed('other', false)], notices: [] })
    await openBoardAndSettings()

    await expand('claude')
    const noHeadless = () => within(card('claude')).getByRole('switch', { name: 'No headless' })
    await userEvent.click(noHeadless())
    expect(within(card('claude')).queryByText('headless')).toBeNull()
    await userEvent.click(noHeadless())

    expect(within(card('claude')).getByText('headless')).toBeTruthy()
    expect(within(card('claude')).getByText('project')).toBeTruthy()
    await save()
    // The override is gone whole: an empty one would still read as «the local
    // layer says something about this entry» (design-00001 §13.1)
    expect(put).toHaveBeenCalledWith({ overrides: {} })
  })

  // spec-00009-FR-7 — an added entry has no other layer to fall back to, so the
  // switch turned back off writes the form on show as edited (design-00002 §18.3)
  it('writes the form on show when the switch is turned off again on an added entry', async () => {
    serve({ local: { entries: { 'codex-local': { command: 'codex', headless: HEADLESS } } } }, [
      listed('claude', true),
      listed('codex-local', true, 'local'),
    ])
    const put = vi
      .spyOn(api, 'saveAgentSettings')
      .mockResolvedValue({ effective: [listed('claude', true), listed('codex-local', true, 'local')], notices: [] })
    await openBoardAndSettings()

    await expand('codex-local')
    const noHeadless = () => within(card('codex-local')).getByRole('switch', { name: 'No headless' })
    await userEvent.click(noHeadless())
    await userEvent.click(noHeadless())
    await save()

    expect(put).toHaveBeenCalledWith({
      entries: { 'codex-local': { command: 'codex', headless: { first: [], resume: [], capture: 'claude-json' } } },
    })
  })

  // spec-00009-AC-8.4 — the board's half: with no headless agent left, neither
  // ask entry is drawn. The refusal of one put through the API is the server's
  it('draws neither ask entry once the one headless agent is disabled and saved', async () => {
    serve({ project: [CLAUDE, OTHER] }, [listed('claude', true), listed('other', false)])
    vi.spyOn(api, 'saveAgentSettings').mockResolvedValue({ effective: [listed('other', false)], notices: [] })
    await openBoard()
    expect(screen.getByRole('button', { name: 'Ask' })).toBeTruthy()
    await openSettings()

    await expand('claude')
    await userEvent.click(within(card('claude')).getByRole('switch', { name: 'Disabled' }))
    await save()
    await closeSettings()

    expect(screen.queryByRole('button', { name: 'Ask' })).toBeNull()
    await userEvent.click(screen.getByRole('button', { name: 'Edit' }))
    await waitFor(() => expect(screen.getByRole('tab', { name: 'Questions' })).toBeTruthy())
    expect(screen.queryByRole('button', { name: 'Ask' })).toBeNull()
  })
})

/**
 * spec-00009-FR-4's panel half: an ill-formed local layer is ignored whole and
 * the panel says why, naming the entry and the key.
 */
describe('what the panel says about an ill-formed local layer', () => {
  const ILL = 'agent settings: .whiteboard/agents.json is not readable JSON — Unexpected end of JSON input'

  // spec-00009-AC-4.1
  it('names a local file that will not parse', async () => {
    serve({ error: { message: ILL } })
    await openBoardAndSettings()

    expect(screen.getByText(ILL)).toBeTruthy()
  })

  // spec-00009-AC-4.2
  it('names the entry whose cwd the local layer tried to override', async () => {
    serve({
      error: {
        message:
          'agent settings: `overrides.claude.cwd` may not be overridden; the working directory is the write-scope barrier',
        at: 'overrides.claude.cwd',
      },
    })
    await openBoardAndSettings()

    expect(screen.getByText(/overrides\.claude\.cwd` may not be overridden/)).toBeTruthy()
    expect(screen.getByText('(overrides.claude.cwd)')).toBeTruthy()
  })

  // spec-00009-AC-4.3
  it('says so when the merge leaves no agent at all', async () => {
    serve({ error: { message: 'agent settings: the effective agent list would be empty' } })
    await openBoardAndSettings()

    expect(screen.getByText('agent settings: the effective agent list would be empty')).toBeTruthy()
  })

  // spec-00009-AC-4.5 — the layer still holds; only the one thing that points at
  // nothing is called out
  it('names an override that points at no project entry', async () => {
    serve({
      local: { overrides: { claude: { model: 'm2' } } },
      notices: [{ name: 'old', message: 'the override of `old` points at no project entry' }],
    })
    await openBoardAndSettings()

    expect(screen.getByText(/the override of `old` points at no project entry/)).toBeTruthy()
    expect(screen.getByText('old')).toBeTruthy()
  })

  // spec-00009-AC-4.6
  it('names a default that points at a disabled entry', async () => {
    serve({
      error: { message: 'agent settings: `claude` is the default and also disabled', at: 'default' },
    })
    await openBoardAndSettings()

    expect(screen.getByText(/`claude` is the default and also disabled/)).toBeTruthy()
  })

  // spec-00009-AC-4.7
  it('names an added entry that declared a cwd of its own', async () => {
    serve({
      error: {
        message:
          'agent settings: `entries.codex-local.cwd` may not be declared; an added entry always runs in `docs`',
        at: 'entries.codex-local.cwd',
      },
    })
    await openBoardAndSettings()

    expect(screen.getByText(/`entries\.codex-local\.cwd` may not be declared/)).toBeTruthy()
  })

  // spec-00009-AC-4.8
  it('names a disable that points at no entry', async () => {
    serve({
      local: { overrides: { claude: { model: 'm2' } }, disabled: ['old'] },
      notices: [{ name: 'old', message: '`old` is disabled, but no entry of that name exists' }],
    })
    await openBoardAndSettings()

    expect(screen.getByText(/`old` is disabled, but no entry of that name exists/)).toBeTruthy()
  })

  // spec-00009-AC-4.9
  it('names an entry the local layer appended over a project one', async () => {
    serve({
      local: { entries: { claude: { command: 'claude' } } },
      error: {
        message: 'agent settings: `claude` has the same name as a project entry; write it under `overrides`',
        at: 'entries.claude',
      },
    })
    await openBoardAndSettings()

    expect(screen.getByText(/`claude` has the same name as a project entry; write it under `overrides`/)).toBeTruthy()
    // The same name twice is one row, not two: the project layer's (design-00001 §13.1).
    expect(cards()).toHaveLength(1)
  })
})

/** The rest of what FR-7 says the panel may do to the local layer. */
describe('editing the local layer', () => {
  // spec-00009-FR-7 — «设为缺省»: the one entry the local layer puts first
  it('names an entry the default', async () => {
    serve({ project: [CLAUDE, OTHER] }, [listed('claude', true), listed('other', false)])
    const put = vi
      .spyOn(api, 'saveAgentSettings')
      .mockResolvedValue({ effective: [listed('other', false), listed('claude', true)], notices: [] })
    await openBoardAndSettings()

    await expand('other')
    await userEvent.click(within(card('other')).getByRole('radio'))

    expect(within(card('other')).getByText('default')).toBeTruthy()
    await save()
    expect(put).toHaveBeenCalledWith({ default: 'other' })
  })

  // spec-00009-AC-7.8's other direction: a value shown is masked again
  it('masks an env value again when it is hidden', async () => {
    serve({ project: [{ ...CLAUDE, env: { FOO: 'bar' } }] })
    await openBoardAndSettings()

    await userEvent.click(screen.getByRole('button', { name: 'Show value' }))
    await userEvent.click(screen.getByRole('button', { name: 'Hide value' }))

    expect(screen.queryByText('bar')).toBeNull()
    expect(screen.getByRole('button', { name: 'Show value' }).getAttribute('aria-pressed')).toBe('false')
  })

  // spec-00009-FR-7 — an override is key-level whole replacement, so one changed
  // argument makes the whole `args` local (design-00001 §13.1)
  it('replaces the whole args key when one element is added or removed', async () => {
    serve()
    const put = vi
      .spyOn(api, 'saveAgentSettings')
      .mockResolvedValue({ effective: [listed('claude', true, 'overridden')], notices: [] })
    await openBoardAndSettings()

    await expand('claude')
    await userEvent.click(screen.getByRole('button', { name: 'Add to arguments' }))
    fireEvent.change(screen.getByLabelText('Arguments 3'), { target: { value: '--verbose' } })
    await userEvent.click(screen.getByRole('button', { name: 'Remove Arguments 1' }))
    await save()

    expect(put).toHaveBeenCalledWith({ overrides: { claude: { args: ['{model}', '--verbose'] } } })
  })

  // spec-00009-FR-7 — env is edited in the clear: the masking is of reading, not
  // of writing (design-00002 §18.3)
  it('adds, edits and removes an environment variable', async () => {
    serve({ project: [{ ...CLAUDE, env: { FOO: 'bar' } }] })
    const put = vi
      .spyOn(api, 'saveAgentSettings')
      .mockResolvedValue({ effective: [listed('claude', true, 'overridden')], notices: [] })
    await openBoardAndSettings()

    await expand('claude')
    await userEvent.click(screen.getByRole('button', { name: 'Add an environment variable' }))
    fireEvent.change(screen.getByLabelText('Environment key 2'), { target: { value: 'BAZ' } })
    fireEvent.change(screen.getByLabelText('Environment value 2'), { target: { value: 'qux' } })
    await userEvent.click(screen.getByRole('button', { name: 'Remove environment 1' }))
    await save()

    expect(put).toHaveBeenCalledWith({ overrides: { claude: { env: { BAZ: 'qux' } } } })
  })

  // spec-00009-FR-7 — «撤销对项目条目的本地覆盖» is per key: the others stay local
  it('leaves the other overrides alone when one is undone', async () => {
    serve({ local: { overrides: { claude: { model: 'm2', command: 'my-claude' } } } }, [
      listed('claude', true, 'overridden'),
    ])
    const put = vi
      .spyOn(api, 'saveAgentSettings')
      .mockResolvedValue({ effective: [listed('claude', true, 'overridden')], notices: [] })
    await openBoardAndSettings()

    await expand('claude')
    await userEvent.click(screen.getByRole('button', { name: 'Undo the local model' }))
    await save()

    expect(put).toHaveBeenCalledWith({ overrides: { claude: { command: 'my-claude' } } })
    expect(within(card('claude')).getByText('project + local override')).toBeTruthy()
  })

  // spec-00009-FR-7 — the capture is chosen from the set the code holds, which
  // the payload names; the panel keeps no list of its own (design-00002 §18.3)
  it('takes the headless capture from the set the payload names', async () => {
    serve({ captures: ['claude-json', 'codex-json'] })
    const put = vi
      .spyOn(api, 'saveAgentSettings')
      .mockResolvedValue({ effective: [listed('claude', true, 'overridden')], notices: [] })
    await openBoardAndSettings()

    await expand('claude')
    await userEvent.click(screen.getByLabelText('Capture'))
    await userEvent.click(await screen.findByRole('option', { name: 'codex-json' }))
    await save()

    expect(put).toHaveBeenCalledWith({
      overrides: { claude: { headless: { ...HEADLESS, capture: 'codex-json' } } },
    })
  })

  // spec-00009-FR-7 — the name of a new entry can be thought better of
  it('lets a new local entry be called off before it is added', async () => {
    serve()
    await openBoardAndSettings()

    await userEvent.click(screen.getByRole('button', { name: /New local agent/ }))
    fireEvent.change(screen.getByLabelText('New agent name'), { target: { value: 'codex-local' } })
    await userEvent.click(screen.getByRole('button', { name: 'Cancel the new agent' }))

    expect(cards()).toHaveLength(1)
    expect(screen.getByRole('button', { name: /New local agent/ })).toBeTruthy()
  })

  // spec-00009-FR-7 — the panel's own read can fail; the board says so and stays up
  it('says so when the settings cannot be read', async () => {
    serve()
    vi.spyOn(api, 'agentSettings').mockRejectedValue(new Error('the board is not answering'))
    const failed = vi.spyOn(toast, 'error').mockImplementation(() => 'id')
    await openBoard()

    await userEvent.click(screen.getByRole('button', { name: 'Agent settings' }))

    await waitFor(() => expect(failed).toHaveBeenCalledWith('the board is not answering'))
    expect(screen.getByText('reading the agent settings…')).toBeTruthy()
  })
})

/** spec-00009-FR-5 and FR-6 as the panel takes them. */
describe('saving from the panel', () => {
  /** The pairing refusal `config.ts` words when a model no `args` stand for is saved (spec-00009-FR-2). */
  const UNPAIRED =
    'config: `overrides.claude.model` is set, so `overrides.claude.args` must hold a `{model}` placeholder'
  /** And the one it words for an added entry with no command of its own. */
  const NO_COMMAND = 'config: `entries.codex-local.command` must be a non-empty string'

  // spec-00009-AC-5.6 — the panel is showing the error of a file that will not
  // parse; a save writes over it all the same
  it('saves over a local file that would not parse', async () => {
    serve({
      local: null,
      error: { message: 'agent settings: .whiteboard/agents.json is not readable JSON — Unexpected end of JSON input' },
    })
    const put = vi
      .spyOn(api, 'saveAgentSettings')
      .mockResolvedValue({ effective: [listed('claude', true, 'overridden')], notices: [] })
    await openBoardAndSettings()

    await expand('claude')
    fireEvent.change(screen.getByLabelText('Model'), { target: { value: 'm2' } })
    await save()

    expect(put).toHaveBeenCalledWith({ overrides: { claude: { model: 'm2' } } })
    await waitFor(() => expect(screen.queryByText(/is not readable JSON/)).toBeNull())
  })

  // spec-00009-AC-6.1 — the refusal lands under the field it is about, and the
  // form keeps what was typed
  it('shows a refused save under the field it names', async () => {
    serve()
    vi.spyOn(api, 'saveAgentSettings').mockRejectedValue(
      new ApiError(422, UNPAIRED, undefined, undefined, 'overrides.claude.args'),
    )
    await openBoardAndSettings()

    await expand('claude')
    fireEvent.change(screen.getByLabelText('Model'), { target: { value: 'm2' } })
    await save()

    expect(await screen.findByText(UNPAIRED)).toBeTruthy()
    expect((screen.getByLabelText('Model') as HTMLInputElement).value).toBe('m2')
  })

  // spec-00009-AC-6.2 — the same content put again is refused the same way
  it('refuses the same content the same way a second time', async () => {
    serve()
    const put = vi
      .spyOn(api, 'saveAgentSettings')
      .mockRejectedValue(
        new ApiError(422, UNPAIRED, undefined, undefined, 'overrides.claude.args'),
      )
    await openBoardAndSettings()

    await expand('claude')
    fireEvent.change(screen.getByLabelText('Model'), { target: { value: 'm2' } })
    await save()
    await screen.findByText(UNPAIRED)
    await save()

    expect(put).toHaveBeenCalledTimes(2)
    expect(put).toHaveBeenLastCalledWith({ overrides: { claude: { model: 'm2' } } })
    expect(screen.getByText(UNPAIRED)).toBeTruthy()
  })

  // spec-00009-AC-6.3
  it('names the command of an added entry that has none', async () => {
    serve()
    vi.spyOn(api, 'saveAgentSettings').mockRejectedValue(
      new ApiError(422, NO_COMMAND, undefined, undefined, 'entries.codex-local.command'),
    )
    await openBoardAndSettings()

    await userEvent.click(screen.getByRole('button', { name: /New local agent/ }))
    fireEvent.change(screen.getByLabelText('New agent name'), { target: { value: 'codex-local' } })
    await userEvent.click(screen.getByRole('button', { name: 'Add' }))
    await save()

    expect(await screen.findByText(NO_COMMAND)).toBeTruthy()
  })

  // spec-00009-FR-6 — a save that never reached the server is reported like any
  // other refusal, and the draft is kept
  it('reports a save that never reached the server', async () => {
    serve()
    vi.spyOn(api, 'saveAgentSettings').mockRejectedValue(new Error('failed to fetch'))
    await openBoardAndSettings()

    await expand('claude')
    fireEvent.change(screen.getByLabelText('Model'), { target: { value: 'm2' } })
    await save()

    expect(await screen.findByText('failed to fetch')).toBeTruthy()
    expect((screen.getByLabelText('Model') as HTMLInputElement).value).toBe('m2')
  })

  // spec-00009-AC-6.5 — a write that failed says so and keeps the form, and
  // saying it twice says it twice
  it('reports a failed write each time, keeping the form', async () => {
    serve()
    const put = vi
      .spyOn(api, 'saveAgentSettings')
      .mockRejectedValue(new ApiError(500, "EACCES: permission denied, open '/repo/.whiteboard/agents.json.tmp'"))
    await openBoardAndSettings()

    await expand('claude')
    fireEvent.change(screen.getByLabelText('Model'), { target: { value: 'm2' } })
    await save()
    expect(await screen.findByText(/could not write \.whiteboard\/agents\.json — EACCES/)).toBeTruthy()

    await save()

    expect(put).toHaveBeenCalledTimes(2)
    expect(screen.getByText(/could not write \.whiteboard\/agents\.json — EACCES/)).toBeTruthy()
    expect((screen.getByLabelText('Model') as HTMLInputElement).value).toBe('m2')
  })
})

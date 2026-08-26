// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { AgentConfig } from '../../src/config.ts'
import type { DocGraph, DocNode } from '../../src/docRepository.ts'
import { Board } from '../src/Board.tsx'
import { type ConfigPayload, api } from '../src/api.ts'

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

/** A headless declaration is what puts an agent in an ask's choice (spec-00005-FR-8). */
const HEADLESS: AgentConfig['headless'] = {
  first: ['-p', '{question}'],
  resume: ['-p', '--resume', '{session}', '{question}'],
  capture: 'claude-json',
}
const CLAUDE: AgentConfig = { name: 'claude', command: 'claude', args: [], headless: { ...HEADLESS } }
const CODEX: AgentConfig = { name: 'codex', command: 'codex', args: [], headless: { ...HEADLESS } }
/** The same agent with no headless form: it runs terminal sessions and answers no question. */
const TERMINAL_ONLY: AgentConfig = { name: 'claude', command: 'claude', args: [] }

/** A started session only needs a socket that answers; the terminal opens one on mount. */
function stubWebSocket() {
  vi.stubGlobal(
    'WebSocket',
    class {
      static readonly OPEN = 1
      readyState = 1
      addEventListener() {}
      send() {}
      close() {}
    },
  )
}

function serve(payload: Partial<ConfigPayload> = {}, nodes: DocNode[] = [node()]) {
  const graph: DocGraph = { nodes, edges: [], issues: [], diagnostics: [], idOwners: {} }
  vi.spyOn(api, 'graph').mockResolvedValue(graph)
  vi.spyOn(api, 'transitions').mockResolvedValue(['active'])
  vi.spyOn(api, 'nextSteps').mockResolvedValue([{ next: 'spec', carry: 'parent' }])
  vi.spyOn(api, 'sessions').mockResolvedValue([])
  vi.spyOn(api, 'items').mockResolvedValue({ items: [], diagnostics: [] })
  vi.spyOn(api, 'doc').mockResolvedValue({ path: 'prd/a.md', content: '# X', hash: 'hash-1' })
  vi.spyOn(api, 'asks').mockResolvedValue([])
  vi.spyOn(api, 'config').mockResolvedValue({
    types: { prd: 'living', spec: 'living', design: 'living' },
    relations: ['parent'],
    flow: {},
    focus: {},
    agents: [CLAUDE],
    entry: [],
    carries: {},
    maxSessions: 3,
    clarifiable: ['prd'],
    auditable: ['spec', 'rule', 'design'],
    ...payload,
  })
}

/** Put the board up and select the one node whose toolbar the case is about. */
async function selectNode(id = 'prd-00001-x') {
  render(<Board />)
  await waitFor(() => expect(screen.getByTestId(`node-${id}`)).toBeTruthy())
  fireEvent.click(screen.getByTestId(`node-${id}`))
  // Edit is on every toolbar, anomalous documents included, so it is what says
  // the toolbar is up without assuming which entries this case expects.
  await waitFor(() => expect(screen.getByRole('button', { name: 'Edit' })).toBeTruthy())
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('the agent picker', () => {
  // spec-00001-AC-55.4 — one agent is no choice, so nothing is drawn
  it('is not drawn when the config declares one agent', async () => {
    serve({ agents: [CLAUDE] })
    await selectNode()

    expect(screen.queryByLabelText('Agent')).toBeNull()
  })

  // spec-00001-FR-55 — more than one, and the choice is on show with the first
  // one standing
  it('is drawn with the first agent chosen when the config declares two', async () => {
    serve({ agents: [CLAUDE, CODEX] })
    await selectNode()

    expect(screen.getByLabelText('Agent').textContent).toContain('claude')
  })

  // spec-00001-AC-55.2 as the board sends it: an untouched picker still names
  // the first agent, which is what the server would have taken anyway
  it('names the first agent on a session it was not asked about', async () => {
    stubWebSocket()
    serve({ agents: [CLAUDE, CODEX] })
    const clarify = vi
      .spyOn(api, 'clarify')
      .mockResolvedValue({ id: 's1', kind: 'clarify', agent: 'claude', sourceId: 'prd-00001-x', status: 'running' })
    await selectNode()

    await userEvent.click(screen.getByRole('button', { name: 'Clarify' }))

    expect(clarify).toHaveBeenCalledWith('prd-00001-x', 'claude')
  })

  // spec-00001-AC-55.1 as the user does it: pick the second, and that is the one
  // the session runs under
  it('sends the agent the user picked', async () => {
    stubWebSocket()
    serve({ agents: [CLAUDE, CODEX] })
    const clarify = vi
      .spyOn(api, 'clarify')
      .mockResolvedValue({ id: 's1', kind: 'clarify', agent: 'codex', sourceId: 'prd-00001-x', status: 'running' })
    await selectNode()

    await userEvent.click(screen.getByLabelText('Agent'))
    await userEvent.click(await screen.findByRole('menuitem', { name: 'codex' }))
    await userEvent.click(screen.getByRole('button', { name: 'Clarify' }))

    expect(clarify).toHaveBeenCalledWith('prd-00001-x', 'codex')
    expect(screen.getByLabelText('Agent').textContent).toContain('codex')
  })

  /** Pick the second agent on a board that has one document selected. */
  async function pickCodex() {
    await selectNode()
    await userEvent.click(screen.getByLabelText('Agent'))
    await userEvent.click(await screen.findByRole('menuitem', { name: 'codex' }))
  }

  // The pick holds for every entry that starts a session, not just the one it
  // was made next to (spec-00001-FR-55). One at a time: the session it starts
  // takes the one slot and locks the others (spec-00001-FR-18).
  it('sends the picked agent on a clarify', async () => {
    stubWebSocket()
    serve({ agents: [CLAUDE, CODEX] })
    const clarify = vi.spyOn(api, 'clarify').mockResolvedValue({
      id: 's1',
      kind: 'clarify',
      agent: 'codex',
      sourceId: 'prd-00001-x',
      status: 'running',
    })
    await pickCodex()

    await userEvent.click(screen.getByRole('button', { name: 'Clarify' }))

    expect(clarify).toHaveBeenCalledWith('prd-00001-x', 'codex')
  })

  it('sends the picked agent on an advance', async () => {
    stubWebSocket()
    serve({ agents: [CLAUDE, CODEX] })
    const advance = vi.spyOn(api, 'advance').mockResolvedValue({
      id: 's1',
      kind: 'advance',
      agent: 'codex',
      sourceId: 'prd-00001-x',
      targetType: 'spec',
      status: 'running',
    })
    await pickCodex()

    await userEvent.click(screen.getByLabelText('Advance to the next step'))
    await userEvent.click(await screen.findByRole('menuitem', { name: /spec/ }))

    expect(advance).toHaveBeenCalledWith('prd-00001-x', 'spec', 'codex')
  })

  // spec-00001-AC-55.4, the other half: with one agent no field is sent at all,
  // so a board against an older server behaves as it always did
  it('names no agent at all when there is only one', async () => {
    stubWebSocket()
    serve({ agents: [CLAUDE] })
    const clarify = vi
      .spyOn(api, 'clarify')
      .mockResolvedValue({ id: 's1', kind: 'clarify', agent: 'claude', sourceId: 'prd-00001-x', status: 'running' })
    await selectNode()

    await userEvent.click(screen.getByRole('button', { name: 'Clarify' }))

    expect(clarify).toHaveBeenCalledWith('prd-00001-x', undefined)
  })
})

/**
 * The ask entry's own choice, which is not the toolbar's: an agent answers a
 * question only if it declares how to be run headlessly (spec-00005-FR-2).
 */
describe('the agent an ask is put to', () => {
  /** Open the question input from the node's floating toolbar. */
  async function openAsk() {
    await userEvent.click(screen.getByRole('button', { name: 'Ask' }))
    return screen.findByLabelText('Question')
  }

  /**
   * spec-00005-AC-2.3 as the user sees it — two agents declared, one of them
   * headless: the ask's choice is that one, and it is no choice at all, so no
   * picker is drawn and no agent is named on the wire.
   */
  it('narrows the choice to the agents that declare a headless form', async () => {
    serve({ agents: [{ ...CODEX, headless: undefined }, CLAUDE] })
    const ask = vi.spyOn(api, 'ask').mockResolvedValue({ sessionId: 's1', threadId: 't-1' })
    await selectNode()

    const question = await openAsk()
    await userEvent.type(question, 'why is this a draft?')
    await userEvent.click(screen.getByRole('button', { name: 'Send' }))

    expect(screen.queryByLabelText('Ask agent')).toBeNull()
    expect(ask).toHaveBeenCalledWith({
      docId: 'prd-00001-x',
      question: 'why is this a draft?',
      agent: undefined,
    })
  })

  // spec-00005-AC-2.3, the other half — both declare one, so both are on offer
  // and the one picked is the one the thread is opened with
  it('puts the question to the headless agent the user picked', async () => {
    serve({ agents: [CLAUDE, CODEX] })
    const ask = vi.spyOn(api, 'ask').mockResolvedValue({ sessionId: 's1', threadId: 't-1' })
    await selectNode()

    const question = await openAsk()
    await userEvent.type(question, 'what is missing here?')
    await userEvent.click(screen.getByLabelText('Ask agent'))
    await userEvent.click(await screen.findByRole('menuitem', { name: 'codex' }))
    await userEvent.click(screen.getByRole('button', { name: 'Send' }))

    expect(ask).toHaveBeenCalledWith({
      docId: 'prd-00001-x',
      question: 'what is missing here?',
      agent: 'codex',
    })
  })

  /**
   * spec-00005-AC-7.4 as the user sees it — no agent declares a headless form,
   * so there is nothing to answer a question and neither entry is drawn: not on
   * the node's toolbar, not in the editor's header.
   */
  it('draws neither ask entry when no agent declares a headless form', async () => {
    serve({ agents: [TERMINAL_ONLY] })
    await selectNode()

    expect(screen.queryByRole('button', { name: 'Ask' })).toBeNull()

    await userEvent.click(screen.getByRole('button', { name: 'Edit' }))

    await waitFor(() => expect(screen.getByRole('tab', { name: 'Questions' })).toBeTruthy())
    expect(screen.queryByRole('button', { name: 'Ask' })).toBeNull()
  })
})

// spec-00001-FR-56: the entries follow the sets in the config payload, and
// nothing else — the front end keeps no copy to drift from.
describe('the entries the config payload allows', () => {
  const DESIGN = node({ id: 'design-00001-x', type: 'design', title: 'Whiteboard UI', path: 'design/a.md' })

  // spec-00001-AC-56.2
  it('draws no audit entry on a draft design when the auditable set omits design', async () => {
    serve({ auditable: ['spec', 'rule'] }, [DESIGN])
    await selectNode('design-00001-x')

    expect(screen.queryByRole('button', { name: 'Audit' })).toBeNull()
  })

  // ...and the same node with design in the set, so the case above is not
  // passing for want of a toolbar
  it('draws it on the same node once the set carries design', async () => {
    serve({ auditable: ['spec', 'rule', 'design'] }, [DESIGN])
    await selectNode('design-00001-x')

    expect(screen.getByRole('button', { name: 'Audit' })).toBeTruthy()
  })

  it('draws the clarify entry only for a type in the clarifiable set', async () => {
    serve({ clarifiable: ['idea'] })
    await selectNode()

    expect(screen.queryByRole('button', { name: 'Clarify' })).toBeNull()
  })

  it('draws it once the set carries the type', async () => {
    serve({ clarifiable: ['prd'] })
    await selectNode()

    expect(screen.getByRole('button', { name: 'Clarify' })).toBeTruthy()
  })
})

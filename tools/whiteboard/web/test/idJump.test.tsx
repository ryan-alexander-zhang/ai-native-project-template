// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { toast } from 'sonner'
import type { DocGraph, DocNode } from '../../src/docRepository.ts'
import type { ItemsView } from '../../src/requirements.ts'
import { api } from '../src/api.ts'
import { InlineMarkdown } from '../src/InlineMarkdown.tsx'
import { Inspector } from '../src/Inspector.tsx'

const setCenter = vi.fn()

// The viewport move is React Flow's to make, so the module is mocked to observe it.
vi.mock('@xyflow/react', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@xyflow/react')>()
  return { ...actual, useReactFlow: () => ({ ...actual.useReactFlow(), setCenter }) }
})

const { Board } = await import('../src/Board.tsx')

function node(overrides: Partial<DocNode> = {}): DocNode {
  return {
    id: 'spec-00001-x',
    path: 'spec/a.md',
    type: 'spec',
    status: 'active',
    title: 'Whiteboard spec',
    relations: {},
    ok: true,
    problems: [],
    ...overrides,
  }
}

/**
 * The resolvable table the server would derive (spec-00001-FR-57): both
 * documents map to themselves, the rule's item to the rule, the spec's items
 * and criteria to the spec — plus one stale row whose owner has already left
 * the board (AC-57.8).
 */
const ID_OWNERS: Record<string, string> = {
  'spec-00001-x': 'spec-00001-x',
  'rule-00001-x': 'rule-00001-x',
  'rule-00001-BR-1': 'rule-00001-x',
  'spec-00001-FR-1': 'spec-00001-x',
  'spec-00001-FR-2': 'spec-00001-x',
  'spec-00001-AC-1.1': 'spec-00001-x',
  'spec-00001-FR-9': 'spec-00099-gone',
}

const GRAPH: DocGraph = {
  nodes: [node(), node({ id: 'rule-00001-x', type: 'rule', title: 'Workflow rule', path: 'rule/a.md' })],
  edges: [],
  issues: [],
  diagnostics: [],
  idOwners: ID_OWNERS,
}

/** FR-1 carries every kind of reference the jump distinguishes; FR-2 carries none. */
const SPEC_ITEMS: ItemsView = {
  items: [
    {
      id: 'spec-00001-FR-1',
      text: 'applies `rule-00001-BR-1` per `rule-00001-x`, cites `spec-00001-AC-1.1`, not `spec-99999-FR-1`, stale `spec-00001-FR-9`',
      criteria: [
        {
          id: 'spec-00001-AC-1.1',
          text: 'Given `spec-00001-FR-2` per `rule-00001-x` When its id is clicked Then the board jumps',
          rows: [],
        },
      ],
      rows: [],
      coverage: 'uncovered',
    },
    { id: 'spec-00001-FR-2', text: 'plain text with no reference', criteria: [], rows: [], coverage: 'uncovered' },
  ],
  diagnostics: [],
}

const RULE_ITEMS: ItemsView = {
  items: [{ id: 'rule-00001-BR-1', text: 'a rule', criteria: [], rows: [], coverage: 'uncovered' }],
  diagnostics: [],
}

afterEach(cleanup)

describe('the code span, on its own', () => {
  const owners = { 'rule-00001-BR-1': 'rule-00001-x', 'spec-00001-FR-1': 'spec-00001-x' }

  it('turns a code span that is exactly one resolvable id into a jump button', () => {
    const onJump = vi.fn()
    render(<InlineMarkdown text={'applies `rule-00001-BR-1`'} idOwners={owners} onJump={onJump} />)

    const button = screen.getByRole('button', { name: 'rule-00001-BR-1' })
    fireEvent.click(button)
    expect(onJump).toHaveBeenCalledWith('rule-00001-x')
  })

  // spec-00001-AC-58.1 — an unresolvable id is not clickable: a click goes nowhere
  it('leaves an unresolvable id inert', () => {
    const onJump = vi.fn()
    const { container } = render(
      <InlineMarkdown text={'not `spec-99999-FR-1`'} idOwners={owners} onJump={onJump} />,
    )

    expect(screen.queryByRole('button')).toBeNull()
    fireEvent.click(container.querySelector('code')!)
    expect(onJump).not.toHaveBeenCalled()
  })

  // spec-00001-AC-58.2 — and it renders as the inline code it always was, unmarked
  it('renders an unresolvable id as plain inline code with no mark', () => {
    const { container } = render(
      <InlineMarkdown text={'not `spec-99999-FR-1`'} idOwners={owners} onJump={vi.fn()} />,
    )

    const code = container.querySelector('code')!
    expect(code.textContent).toBe('spec-99999-FR-1')
    expect(container.querySelector('.underline')).toBeNull()
    expect(container.textContent).toBe('not spec-99999-FR-1')
  })

  // spec-00001-AC-58.3 — an id in bare prose is not recognised
  it('makes nothing clickable of an id outside backticks', () => {
    render(<InlineMarkdown text={'applies rule-00001-BR-1 as prose'} idOwners={owners} onJump={vi.fn()} />)

    expect(screen.queryByRole('button')).toBeNull()
  })

  // spec-00001-AC-58.4 — a span carrying anything beside the id is not recognised
  it('makes nothing clickable of a span that is more than the id', () => {
    render(
      <InlineMarkdown text={'declared as `verifies: [spec-00001-FR-1]`'} idOwners={owners} onJump={vi.fn()} />,
    )

    expect(screen.queryByRole('button')).toBeNull()
  })

  // spec-00001-AC-59.2 — underline, not colour alone, tells the two spans apart
  it('underlines the clickable span and not its plain neighbour', () => {
    const { container } = render(
      <InlineMarkdown text={'see `rule-00001-BR-1` and `plain code`'} idOwners={owners} onJump={vi.fn()} />,
    )

    const button = screen.getByRole('button', { name: 'rule-00001-BR-1' })
    expect(button.className).toContain('underline')
    const codes = Array.from(container.querySelectorAll('code'))
    const plain = codes.find((code) => code.textContent === 'plain code')!
    expect(plain.closest('button')).toBeNull()
  })

  // spec-00001-AC-59.3 — a button, never a link element carrying an external URL
  it('puts no anchor on the page', () => {
    const { container } = render(
      <InlineMarkdown text={'see `rule-00001-BR-1`'} idOwners={owners} onJump={vi.fn()} />,
    )

    expect(container.querySelector('a')).toBeNull()
    expect(container.querySelector('[href]')).toBeNull()
  })

  it('renders every span as plain code when the jump is not wired', () => {
    const { container } = render(<InlineMarkdown text={'applies `rule-00001-BR-1`'} idOwners={owners} />)

    expect(screen.queryByRole('button')).toBeNull()
    expect(container.querySelector('code')!.textContent).toBe('rule-00001-BR-1')
  })

  it('renders plain code when no table came with the callback', () => {
    const { container } = render(<InlineMarkdown text={'applies `rule-00001-BR-1`'} onJump={vi.fn()} />)

    expect(screen.queryByRole('button')).toBeNull()
    expect(container.querySelector('code')!.textContent).toBe('rule-00001-BR-1')
  })

  // Only Enter is the button's own gesture; any other key belongs to whoever is listening above.
  it('lets a key other than Enter pass without jumping', () => {
    const onJump = vi.fn()
    render(<InlineMarkdown text={'see `rule-00001-BR-1`'} idOwners={owners} onJump={onJump} />)

    fireEvent.keyDown(screen.getByRole('button', { name: 'rule-00001-BR-1' }), { key: 'a' })

    expect(onJump).not.toHaveBeenCalled()
  })
})

describe('the inspector row, on its own', () => {
  function renderInspector(onJump: (docId: string) => void) {
    return render(
      <Inspector
        docId="spec-00001-x"
        view={SPEC_ITEMS}
        onInspect={() => {}}
        onExpand={() => {}}
        idOwners={ID_OWNERS}
        onJump={onJump}
      />,
    )
  }

  // spec-00001-AC-57.4 — the id click jumps; the collapsed row does not expand
  it('jumps from the truncated row without expanding it', () => {
    const onJump = vi.fn()
    renderInspector(onJump)

    const row = screen.getByTestId('item-spec-00001-FR-1')
    expect(row.getAttribute('aria-expanded')).toBe('false')
    fireEvent.click(within(row).getByRole('button', { name: 'rule-00001-BR-1' }))

    expect(onJump).toHaveBeenCalledWith('rule-00001-x')
    expect(row.getAttribute('aria-expanded')).toBe('false')
  })

  // spec-00001-AC-57.5 — Enter on the focused id is the same jump, and the row stays put
  it('jumps on Enter exactly as on click, leaving the expansion state alone', async () => {
    const onJump = vi.fn()
    renderInspector(onJump)

    const row = screen.getByTestId('item-spec-00001-FR-1')
    within(row).getByRole('button', { name: 'rule-00001-BR-1' }).focus()
    await userEvent.keyboard('{Enter}')

    expect(onJump).toHaveBeenCalledWith('rule-00001-x')
    expect(row.getAttribute('aria-expanded')).toBe('false')
  })
})

describe('the jump, across the board', () => {
  beforeEach(() => {
    setCenter.mockClear()
    vi.spyOn(api, 'graph').mockResolvedValue(GRAPH)
    vi.spyOn(api, 'transitions').mockResolvedValue(['archived'])
    vi.spyOn(api, 'nextSteps').mockResolvedValue([])
    vi.spyOn(api, 'session').mockResolvedValue({ current: null })
    vi.spyOn(api, 'items').mockImplementation(async (id) => (id === 'rule-00001-x' ? RULE_ITEMS : SPEC_ITEMS))
    vi.spyOn(api, 'config').mockResolvedValue({
      types: { spec: 'living', rule: 'living' },
      relations: ['verifies'],
      flow: {},
      focus: {},
      agents: [{ name: 'claude', command: 'claude', args: [] }],
      entry: [],
      carries: {},
      clarifiable: [],
      auditable: ['spec', 'rule', 'design'],
    })
  })

  afterEach(() => vi.restoreAllMocks())

  async function openPanel() {
    render(<Board />)
    await waitFor(() => expect(screen.getByTestId('node-spec-00001-x')).toBeTruthy())
    fireEvent.click(screen.getByTestId('node-spec-00001-x'))
    await waitFor(() => expect(screen.getByTestId('item-spec-00001-FR-1')).toBeTruthy())
  }

  async function openSubCanvas() {
    await openPanel()
    await userEvent.click(screen.getByRole('button', { name: /Expand as sub-canvas/ }))
    await waitFor(() => expect(screen.getByTestId('sub-item-spec-00001-FR-1')).toBeTruthy())
  }

  async function openItemDetail() {
    await openSubCanvas()
    fireEvent.click(screen.getByTestId('sub-item-spec-00001-FR-1'))
    return await screen.findByLabelText('Details of spec-00001-FR-1')
  }

  // spec-00001-AC-57.1 — an item id in the detail panel lands on its owning document
  it('jumps from the detail panel to the document owning the item id', async () => {
    const panel = await openItemDetail()

    fireEvent.click(within(panel).getByRole('button', { name: 'rule-00001-BR-1' }))

    await waitFor(() => expect(screen.getByLabelText('Requirements of rule-00001-x')).toBeTruthy())
    expect(screen.queryByRole('button', { name: 'Board' })).toBeNull()
    expect(setCenter).toHaveBeenCalled()
  })

  // spec-00001-AC-57.2 — a document id in the expanded row selects and centres that node
  it('jumps from the expanded row to the document the id names', async () => {
    await openPanel()
    await userEvent.click(screen.getByTestId('item-spec-00001-FR-1'))
    const expansion = screen.getByLabelText('Expanded spec-00001-FR-1')

    fireEvent.click(within(expansion).getByRole('button', { name: 'rule-00001-x' }))

    await waitFor(() => expect(screen.getByLabelText('Requirements of rule-00001-x')).toBeTruthy())
    expect(setCenter).toHaveBeenCalled()
  })

  // spec-00001-AC-57.3 — the id click in a sub-canvas node jumps instead of opening its detail
  it('jumps from a sub-canvas node rather than opening the detail panel', async () => {
    await openSubCanvas()
    const card = screen.getByTestId('sub-item-spec-00001-FR-1')

    fireEvent.click(within(card).getByRole('button', { name: 'spec-00001-AC-1.1' }))

    await waitFor(() => expect(screen.getByLabelText('Requirements of spec-00001-x')).toBeTruthy())
    expect(screen.queryByLabelText('Details of spec-00001-FR-1')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Board' })).toBeNull()
  })

  // spec-00001-AC-57.6 — a self-reference takes the same path: back on top, still selected
  it('returns to the top board with the same document selected on a self-reference', async () => {
    await openSubCanvas()
    fireEvent.click(screen.getByTestId('sub-ac-spec-00001-AC-1.1'))
    const panel = await screen.findByLabelText('Details of spec-00001-AC-1.1')

    fireEvent.click(within(panel).getByRole('button', { name: 'spec-00001-FR-2' }))

    await waitFor(() => expect(screen.getByLabelText('Requirements of spec-00001-x')).toBeTruthy())
    expect(screen.queryByRole('button', { name: 'Board' })).toBeNull()
  })

  // spec-00001-AC-57.7 — the right slot follows the target: detail closed, inspector on show
  it('hands the right slot to the target document’s inspector', async () => {
    const panel = await openItemDetail()

    fireEvent.click(within(panel).getByRole('button', { name: 'rule-00001-BR-1' }))

    await waitFor(() => expect(screen.getByLabelText('Requirements of rule-00001-x')).toBeTruthy())
    expect(screen.queryByLabelText(/Details of/)).toBeNull()
  })

  // spec-00001-AC-57.8 — a stale target refuses in place: the sub-canvas and detail stay
  it('refuses a jump whose document has left the board and moves nothing', async () => {
    const toastError = vi.spyOn(toast, 'error').mockImplementation(() => 'id')
    const panel = await openItemDetail()
    setCenter.mockClear()

    fireEvent.click(within(panel).getByRole('button', { name: 'spec-00001-FR-9' }))

    await waitFor(() =>
      expect(toastError).toHaveBeenCalledWith('no document spec-00099-gone on the board'),
    )
    expect(screen.getByLabelText('Details of spec-00001-FR-1')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Board' })).toBeTruthy()
    expect(setCenter).not.toHaveBeenCalled()
  })
})

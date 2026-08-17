// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { DocNode } from '../../src/docRepository.ts'
import { TooltipProvider } from '@/components/ui/tooltip'
import { Toolbar, type ToolbarProps } from '../src/Toolbar.tsx'

afterEach(cleanup)

const NODE: DocNode = {
  id: 'prd-00001-x',
  path: 'prd/a.md',
  type: 'prd',
  status: 'draft',
  title: 'X',
  relations: {},
  ok: true,
  problems: [],
}

function renderToolbar(overrides: Partial<ToolbarProps> = {}) {
  const props: ToolbarProps = {
    node: NODE,
    transitions: ['active', 'archived'],
    nextSteps: [{ next: 'spec', carry: 'parent' }],
    relations: [],
    clarifiable: true,
    sessionRunning: false,
    onPickRelation: vi.fn(),
    onEdit: vi.fn(),
    onStatus: vi.fn(),
    onAccept: vi.fn(),
    onClarify: vi.fn(),
    onAsk: vi.fn(),
    onAdvance: vi.fn(),
    ...overrides,
  }
  render(
    <TooltipProvider>
      <Toolbar {...props} />
    </TooltipProvider>,
  )
  return props
}

// spec-00001-AC-3.1
describe('the floating toolbar', () => {
  it('offers edit, status, review, ask, and advance', () => {
    renderToolbar()

    expect(screen.getByRole('button', { name: 'Edit' })).toBeTruthy()
    expect(screen.getByLabelText('Change status')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Accept' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Clarify' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Ask' })).toBeTruthy()
    expect(screen.getByLabelText('Advance to the next step')).toBeTruthy()
  })

  // spec-00001-AC-2.4 and AC-47.4 — amended with FR-30: the anomalous node keeps
  // the two read-only entries it needs to be repaired, and nothing else. Ask is
  // among the ones it does not get, though it changes no status of its own: the
  // session would be told to read a document whose front matter cannot be read.
  it('offers only the editor and the relation list for a document with front matter problems', () => {
    renderToolbar({ node: { ...NODE, ok: false, problems: ['front matter is missing'] } })

    expect(screen.getByRole('button', { name: 'Edit' })).toBeTruthy()
    expect(screen.getByLabelText('Relations')).toBeTruthy()
    expect(screen.queryByLabelText('Change status')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Accept' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Clarify' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Ask' })).toBeNull()
    expect(screen.queryByLabelText('Advance to the next step')).toBeNull()
  })

  // spec-00001-AC-30.4
  it('says there are no relations rather than showing an empty list', async () => {
    renderToolbar({ relations: [] })

    await userEvent.click(screen.getByLabelText('Relations'))

    expect(await screen.findByText('no relations')).toBeTruthy()
  })

  // spec-00001-AC-30.5 — a broken relation is exactly what the reader needs
  it('marks a relation whose target does not exist', async () => {
    renderToolbar({
      relations: [
        { field: 'parent', direction: 'out', otherId: 'idea-09999-ghost', targetId: 'idea-09999-ghost', ok: false },
      ],
    })

    await userEvent.click(screen.getByLabelText('Relations'))

    expect(await screen.findByText('missing')).toBeTruthy()
    expect(screen.getByText('idea-09999-ghost')).toBeTruthy()
  })

  // spec-00001-AC-30.2 — direction is stated, not left to be inferred
  it('states which end declared each relation', async () => {
    renderToolbar({
      relations: [
        { field: 'parent', direction: 'out', otherId: 'idea-00001-x', targetId: 'idea-00001-x', ok: true },
        { field: 'implements', direction: 'in', otherId: 'plan-00001-x', targetId: 'plan-00001-x', ok: true },
      ],
    })

    await userEvent.click(screen.getByLabelText('Relations'))

    expect(await screen.findByText('declared here, points at')).toBeTruthy()
    expect(screen.getByText('declared by')).toBeTruthy()
  })

  // spec-00001-AC-30.3
  it('hands back the document picked from the list', async () => {
    const props = renderToolbar({
      relations: [{ field: 'parent', direction: 'out', otherId: 'idea-00001-x', targetId: 'idea-00001-x', ok: true }],
    })

    await userEvent.click(screen.getByLabelText('Relations'))
    await userEvent.click(await screen.findByText('idea-00001-x'))

    expect(props.onPickRelation).toHaveBeenCalledWith('idea-00001-x')
  })

  // spec-00001-AC-2.6 and AC-28.5 — a fine-grained reference is listed as it was
  // declared, and going to it goes to the document that holds the item.
  it('lists each declared item id and jumps to the document holding it', async () => {
    const props = renderToolbar({
      relations: [
        { field: 'verifies', direction: 'out', otherId: 'spec-00001-FR-28', targetId: 'spec-00001-board', ok: true },
        { field: 'verifies', direction: 'out', otherId: 'spec-00001-FR-29', targetId: 'spec-00001-board', ok: true },
      ],
    })

    await userEvent.click(screen.getByLabelText('Relations'))

    expect(await screen.findByText('spec-00001-FR-28')).toBeTruthy()
    expect(screen.getByText('spec-00001-FR-29')).toBeTruthy()
    await userEvent.click(screen.getByText('spec-00001-FR-28'))

    expect(props.onPickRelation).toHaveBeenCalledWith('spec-00001-board')
  })

  it('opens the editor when edit is pressed', async () => {
    const props = renderToolbar()
    await userEvent.click(screen.getByRole('button', { name: 'Edit' }))
    expect(props.onEdit).toHaveBeenCalled()
  })

  // spec-00001-AC-6.1 as the user sees it
  it('lists only the legal target statuses', async () => {
    renderToolbar()

    await userEvent.click(screen.getByLabelText('Change status'))

    expect(screen.getAllByRole('menuitem').map((item) => item.textContent)).toEqual(['active', 'archived'])
  })

  it('reports the chosen status', async () => {
    const props = renderToolbar()

    await userEvent.click(screen.getByLabelText('Change status'))
    await userEvent.click(screen.getByRole('menuitem', { name: 'active' }))

    expect(props.onStatus).toHaveBeenCalledWith('active')
  })

  it('accepts the document', async () => {
    const props = renderToolbar()
    await userEvent.click(screen.getByRole('button', { name: 'Accept' }))
    expect(props.onAccept).toHaveBeenCalled()
  })

  // spec-00001-AC-9.1 as the user sees it: one press starts the session, and the
  // questions come from the agent in the terminal, not from a form here.
  it('starts a clarify session on one press', async () => {
    const props = renderToolbar()

    await userEvent.click(screen.getByRole('button', { name: 'Clarify' }))

    expect(props.onClarify).toHaveBeenCalledTimes(1)
  })

  // spec-00001-AC-9.3 — a record is not a clarifiable type, so the entry is not
  // there at all. Accept and ask are, so this is the entry going, not the group.
  it('leaves clarify out for a type the config gives no focus line', () => {
    renderToolbar({ node: { ...NODE, id: 'record-00001-x', type: 'record' }, clarifiable: false })

    expect(screen.queryByRole('button', { name: 'Clarify' })).toBeNull()
    expect(screen.getByRole('button', { name: 'Accept' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Ask' })).toBeTruthy()
  })

  // spec-00001-FR-9: the entry follows the type, never the status — a clarify of
  // a non-`draft` document is refused by the server, not hidden here.
  it('shows clarify whatever the status of a clarifiable type', () => {
    renderToolbar({ node: { ...NODE, status: 'active' } })

    expect(screen.getByRole('button', { name: 'Clarify' })).toBeTruthy()
  })

  // spec-00001-AC-47.1 as the user sees it — any type, any status
  it('starts an ask session from an active record node', async () => {
    const props = renderToolbar({
      node: { ...NODE, id: 'record-00001-x', type: 'record', status: 'active' },
      clarifiable: false,
    })

    await userEvent.click(screen.getByRole('button', { name: 'Ask' }))

    expect(props.onAsk).toHaveBeenCalledTimes(1)
  })

  // spec-00001-AC-18.2 at the entry: one session runs, so none of the three
  // starting points can begin another.
  it('disables advance, clarify, and ask while a session is running', () => {
    renderToolbar({ sessionRunning: true })

    expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Clarify' }).disabled).toBe(true)
    expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Ask' }).disabled).toBe(true)
    expect(screen.getByLabelText<HTMLButtonElement>('Advance to the next step').disabled).toBe(true)
    // Accept writes nothing to the session slot, so it stays available.
    expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Accept' }).disabled).toBe(false)
  })

  /**
   * spec-00001-AC-49.5 — a disabled entry that says nothing leaves the user with
   * no way to tell a locked board from a broken one (issue-00010). The reason is
   * read the way the «no next step» one is: focus the entry, and it is announced.
   */
  it.each(['Clarify', 'Ask', 'Advance to the next step'])(
    'says why %s is disabled while a session runs',
    async (name) => {
      renderToolbar({ sessionRunning: true })

      // The disabled button takes no pointer events, so the reason hangs on the
      // wrapper — which is also what makes it reachable by keyboard.
      await userEvent.hover(screen.getByRole('button', { name }).parentElement!)

      expect((await screen.findByRole('tooltip')).textContent).toContain('session running')
    },
  )

  /**
   * spec-00001-FR-49 and design-00002 §3: when both reasons hold, «no next step»
   * is the one shown — it outlives the session, so telling the user only about
   * the session would promise an entry that never arrives.
   */
  it('prefers no next step over session running when both hold', async () => {
    renderToolbar({ nextSteps: [], sessionRunning: true })

    await userEvent.hover(screen.getByLabelText('Advance to the next step').parentElement!)

    expect((await screen.findByRole('tooltip')).textContent).toContain('no next step')
  })

  // spec-00001-AC-10.2
  it('lists every next-step candidate', async () => {
    renderToolbar({
      nextSteps: [
        { next: 'prd', carry: 'parent' },
        { next: 'spec', carry: 'parent' },
      ],
    })

    await userEvent.click(screen.getByLabelText('Advance to the next step'))

    expect(screen.getAllByRole('menuitem').map((item) => item.textContent)).toEqual([
      'prdparent',
      'specparent',
    ])
  })

  // spec-00001-AC-10.3
  it('says there is no next step and stays disabled when the flow declares none', () => {
    renderToolbar({ nextSteps: [] })
    const trigger = screen.getByLabelText<HTMLButtonElement>('Advance to the next step')

    expect(trigger.disabled).toBe(true)
    expect(trigger.textContent).toContain('no next step')
  })

  it('reports the chosen next step', async () => {
    const props = renderToolbar()

    await userEvent.click(screen.getByLabelText('Advance to the next step'))
    await userEvent.click(screen.getByRole('menuitem', { name: /spec/ }))

    expect(props.onAdvance).toHaveBeenCalledWith('spec')
  })
})

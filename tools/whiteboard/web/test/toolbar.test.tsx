// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { DocNode } from '../../src/docRepository.ts'
import { TooltipProvider } from '@/components/ui/tooltip'
import { CAP_REACHED, DOC_BUSY, Toolbar, type ToolbarProps } from '../src/Toolbar.tsx'

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
    auditable: false,
    docBusy: false,
    capReached: false,
    cowriting: false,
    agents: ['claude'],
    askAgents: ['claude'],
    onPickAgent: vi.fn(),
    onPickRelation: vi.fn(),
    onEdit: vi.fn(),
    onStatus: vi.fn(),
    onAccept: vi.fn(),
    onClarify: vi.fn(),
    // A question that went: what a submit gets back is whether it did (spec-00005-FR-7).
    onAsk: vi.fn(async () => true),
    knownDoc: () => true,
    // A cowrite that started: a submit gets back whether it did (spec-00006-FR-9).
    onCowrite: vi.fn(async () => true),
    onAudit: vi.fn(),
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

  /**
   * spec-00002-AC-9.1: a node that collides on its id is an anomalous node and
   * nothing more — spec-00001-FR-2 already rules its toolbar, so FR-9 adds no
   * presentation of its own. The editor it keeps addresses the node's own file
   * path, which is the repair path (spec-00002-FR-9 b).
   */
  it('offers a colliding document the same editor-only toolbar', () => {
    renderToolbar({
      node: {
        ...NODE,
        id: 'spec/second.md',
        path: 'spec/second.md',
        type: 'spec',
        duplicateOf: 'spec-00002-clash',
        ok: false,
        problems: ['id "spec-00002-clash" is also declared by spec/first.md'],
      },
    })

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

  /**
   * spec-00005-AC-1.3 as the user sees it — any type, any status: the entry is
   * on an active record's toolbar too, and it opens the question input rather
   * than a session (design-00002 §14).
   */
  it('opens the question input from an active record node', async () => {
    const props = renderToolbar({
      node: { ...NODE, id: 'record-00001-x', type: 'record', status: 'active' },
      clarifiable: false,
    })

    await userEvent.click(screen.getByRole('button', { name: 'Ask' }))
    await userEvent.type(await screen.findByLabelText('Question'), 'what did this record verify?')
    await userEvent.click(screen.getByRole('button', { name: 'Send' }))

    expect(props.onAsk).toHaveBeenCalledWith('what did this record verify?', undefined)
  })

  /**
   * spec-00005-FR-6 at the entry — a running session on this document locks the
   * other starting points, and asking is not one of them: a question holds no
   * document, and one call per thread is the server's ruling, not the toolbar's.
   */
  it('leaves the ask entry alone while this document has a session', () => {
    renderToolbar({ docBusy: true })

    expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Ask' }).disabled).toBe(false)
  })

  /**
   * spec-00003-FR-3 at the entry — an ask takes a session slot like any other
   * kind, so at the cap there is nothing to run it: the entry is locked and says
   * which rule holds, exactly as the other starting points do
   * (spec-00001-AC-49.5).
   */
  it('locks the ask entry at the cap and says why', async () => {
    renderToolbar({ capReached: true })
    const ask = screen.getByRole<HTMLButtonElement>('button', { name: 'Ask' })

    expect(ask.disabled).toBe(true)
    await userEvent.hover(ask.parentElement!)

    const tooltip = await screen.findByRole('tooltip')
    expect(tooltip.textContent).toContain(CAP_REACHED)
    expect(tooltip.textContent).not.toContain(DOC_BUSY)
  })

  // spec-00005-AC-7.4 at the entry — no agent declares a headless form, so there
  // is nothing to put a question to and the entry is not drawn
  it('draws no ask entry when no agent declares a headless form', () => {
    renderToolbar({ askAgents: [] })

    expect(screen.queryByRole('button', { name: 'Ask' })).toBeNull()
    expect(screen.getByRole('button', { name: 'Clarify' })).toBeTruthy()
  })

  // design-00002 §14 — an empty question is nothing to ask, so it cannot be sent
  it('refuses to send an empty question', async () => {
    const props = renderToolbar()

    await userEvent.click(screen.getByRole('button', { name: 'Ask' }))
    await screen.findByLabelText('Question')

    expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Send' }).disabled).toBe(true)
    expect(props.onAsk).not.toHaveBeenCalled()
  })

  // spec-00001-AC-50.1 as the user sees it — the entry is there for each type the
  // payload calls auditable, and only while the document is still a `draft`.
  // Which types those are is the payload's word, not the toolbar's
  // (spec-00001-FR-56).
  it.each([
    ['spec', 'spec-00001-x'],
    ['rule', 'rule-00001-x'],
    ['design', 'design-00001-x'],
  ])('shows the audit button on a draft %s node', (type, id) => {
    renderToolbar({ node: { ...NODE, id, type, status: 'draft' }, auditable: true })

    expect(screen.getByRole('button', { name: 'Audit' })).toBeTruthy()
  })

  // spec-00001-AC-51.2 — the entry follows the status too: an audit is the gate
  // before review, and a document that is past `draft` is past the gate.
  it('leaves audit out for a spec that is no longer a draft', () => {
    renderToolbar({ node: { ...NODE, id: 'spec-00001-x', type: 'spec', status: 'active' }, auditable: true })

    expect(screen.queryByRole('button', { name: 'Audit' })).toBeNull()
    expect(screen.getByRole('button', { name: 'Ask' })).toBeTruthy()
  })

  // spec-00001-AC-51.1 and AC-56.2 as the user sees them — a prd is not in the
  // payload's auditable set, so no entry is drawn whatever its status
  it('leaves audit out for a type that cannot be audited', () => {
    renderToolbar({ auditable: false })

    expect(screen.queryByRole('button', { name: 'Audit' })).toBeNull()
  })

  // spec-00001-AC-51.3 as the user sees it — a document whose front matter
  // cannot be read is not something an audit can be pointed at
  it('leaves audit out for an anomalous draft spec', () => {
    renderToolbar({
      node: { ...NODE, id: 'spec-00001-x', type: 'spec', ok: false, problems: ['front matter is missing'] },
      auditable: true,
    })

    expect(screen.queryByRole('button', { name: 'Audit' })).toBeNull()
  })

  // spec-00001-AC-50.1 — one press, and the auditing happens in the terminal
  it('starts an audit session on one press', async () => {
    const props = renderToolbar({ node: { ...NODE, id: 'spec-00001-x', type: 'spec' }, auditable: true })

    await userEvent.click(screen.getByRole('button', { name: 'Audit' }))

    expect(props.onAudit).toHaveBeenCalledTimes(1)
  })

  // spec-00003-AC-2.4 and spec-00001-AC-49.5 — audit is a fourth starting point,
  // so this document's own running session locks it on the same terms as the
  // other three, and for the same stated reason.
  it('disables audit while this document has a session and says why', async () => {
    renderToolbar({ node: { ...NODE, id: 'spec-00001-x', type: 'spec' }, auditable: true, docBusy: true })
    const audit = screen.getByRole<HTMLButtonElement>('button', { name: 'Audit' })

    expect(audit.disabled).toBe(true)
    await userEvent.hover(audit.parentElement!)

    expect((await screen.findByRole('tooltip')).textContent).toContain(DOC_BUSY)
  })

  // spec-00003-AC-2.4 at the entry: this document has a session, so none of its
  // starting points can begin another.
  it('disables advance and clarify while this document has a session', () => {
    renderToolbar({ docBusy: true })

    expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Clarify' }).disabled).toBe(true)
    expect(screen.getByLabelText<HTMLButtonElement>('Advance to the next step').disabled).toBe(true)
    // Accept takes no session slot, so it stays available.
    expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Accept' }).disabled).toBe(false)
  })

  // spec-00003-AC-3.1 at the entry: the cap is reached, so a document with no
  // session of its own cannot start one either.
  it('disables the starting points of a free document while the cap is reached', () => {
    renderToolbar({ capReached: true })

    expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Clarify' }).disabled).toBe(true)
    expect(screen.getByLabelText<HTMLButtonElement>('Advance to the next step').disabled).toBe(true)
  })

  /**
   * spec-00001-AC-49.5 — a disabled entry that says nothing leaves the user with
   * no way to tell a locked board from a broken one (issue-00010). The reason is
   * read the way the «no next step» one is: focus the entry, and it is announced.
   */
  it.each(['Clarify', 'Advance to the next step'])(
    'says %s is disabled because this document has a session',
    async (name) => {
      renderToolbar({ docBusy: true })

      // The disabled button takes no pointer events, so the reason hangs on the
      // wrapper — which is also what makes it reachable by keyboard.
      await userEvent.hover(screen.getByRole('button', { name }).parentElement!)

      expect((await screen.findByRole('tooltip')).textContent).toContain(DOC_BUSY)
    },
  )

  // spec-00001-AC-49.11 (sixteenth round) — the other reason, on a document that
  // has no session of its own: every slot is taken.
  it.each(['Clarify', 'Advance to the next step'])(
    'says %s is disabled because the cap is reached',
    async (name) => {
      renderToolbar({ capReached: true })

      await userEvent.hover(screen.getByRole('button', { name }).parentElement!)

      expect((await screen.findByRole('tooltip')).textContent).toContain(CAP_REACHED)
    },
  )

  /**
   * spec-00001-FR-49 and design-00002 §3: with both concurrency reasons holding,
   * this document's own session is the one named — the more specific of the two,
   * and the one the user can wait out (spec-00003-FR-2).
   */
  it('prefers this document own session over the cap when both hold', async () => {
    renderToolbar({ docBusy: true, capReached: true })

    await userEvent.hover(screen.getByRole('button', { name: 'Clarify' }).parentElement!)

    const tooltip = await screen.findByRole('tooltip')
    expect(tooltip.textContent).toContain(DOC_BUSY)
    expect(tooltip.textContent).not.toContain(CAP_REACHED)
  })

  /**
   * spec-00001-AC-49.5's priority clause: when «no next step» holds too, it is the
   * one shown — it outlives every session, so naming a session would promise an
   * entry that never arrives.
   */
  it('prefers no next step over both concurrency reasons', async () => {
    renderToolbar({ nextSteps: [], docBusy: true, capReached: true })

    await userEvent.hover(screen.getByLabelText('Advance to the next step').parentElement!)

    const tooltip = await screen.findByRole('tooltip')
    expect(tooltip.textContent).toContain('no next step')
    expect(tooltip.textContent).not.toContain(DOC_BUSY)
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

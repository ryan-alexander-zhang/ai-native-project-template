// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { DocNode } from '../../src/docRepository.ts'
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
    onEdit: vi.fn(),
    onStatus: vi.fn(),
    onAccept: vi.fn(),
    onClarify: vi.fn(),
    onAdvance: vi.fn(),
    ...overrides,
  }
  render(<Toolbar {...props} />)
  return props
}

// spec-00001-AC-3.1
describe('the floating toolbar', () => {
  it('offers edit, status, review, and advance', () => {
    renderToolbar()

    expect(screen.getByRole('button', { name: 'Edit' })).toBeTruthy()
    expect(screen.getByLabelText('Change status')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Accept' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Clarify' })).toBeTruthy()
    expect(screen.getByLabelText('Advance to the next step')).toBeTruthy()
  })

  // spec-00001-AC-2.4
  it('offers only the editor for a document with front matter problems', () => {
    renderToolbar({ node: { ...NODE, ok: false, problems: ['front matter is missing'] } })

    expect(screen.getByRole('button', { name: 'Edit' })).toBeTruthy()
    expect(screen.queryByLabelText('Change status')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Accept' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Clarify' })).toBeNull()
    expect(screen.queryByLabelText('Advance to the next step')).toBeNull()
  })

  it('opens the editor when edit is pressed', async () => {
    const props = renderToolbar()
    await userEvent.click(screen.getByRole('button', { name: 'Edit' }))
    expect(props.onEdit).toHaveBeenCalled()
  })

  // spec-00001-AC-6.1 as the user sees it
  it('lists only the legal target statuses', () => {
    renderToolbar()
    const options = Array.from(screen.getByLabelText<HTMLSelectElement>('Change status').options, (o) => o.value)

    expect(options).toEqual(['', 'active', 'archived'])
  })

  it('reports the chosen status', async () => {
    const props = renderToolbar()
    await userEvent.selectOptions(screen.getByLabelText('Change status'), 'active')
    expect(props.onStatus).toHaveBeenCalledWith('active')
  })

  it('accepts the document', async () => {
    const props = renderToolbar()
    await userEvent.click(screen.getByRole('button', { name: 'Accept' }))
    expect(props.onAccept).toHaveBeenCalled()
  })

  // spec-00001-AC-9.3 as the user sees it
  it('records every question typed, one per line', async () => {
    const props = renderToolbar()

    await userEvent.click(screen.getByRole('button', { name: 'Clarify' }))
    await userEvent.type(screen.getByLabelText('Open questions, one per line'), 'who owns pricing?\nwhen is v1?')
    await userEvent.click(screen.getByRole('button', { name: 'Record questions' }))

    expect(props.onClarify).toHaveBeenCalledWith(['who owns pricing?', 'when is v1?'])
  })

  it('records nothing when the clarify form is left empty', async () => {
    const props = renderToolbar()

    await userEvent.click(screen.getByRole('button', { name: 'Clarify' }))
    await userEvent.click(screen.getByRole('button', { name: 'Record questions' }))

    expect(props.onClarify).not.toHaveBeenCalled()
    expect(screen.queryByLabelText('Open questions, one per line')).toBeNull()
  })

  it('closes the clarify form when clarify is pressed again', async () => {
    renderToolbar()

    await userEvent.click(screen.getByRole('button', { name: 'Clarify' }))
    await userEvent.click(screen.getByRole('button', { name: 'Clarify' }))

    expect(screen.queryByLabelText('Open questions, one per line')).toBeNull()
  })

  // spec-00001-AC-10.2
  it('lists every next-step candidate', () => {
    renderToolbar({
      nextSteps: [
        { next: 'prd', carry: 'parent' },
        { next: 'spec', carry: 'parent' },
      ],
    })
    const options = Array.from(screen.getByLabelText<HTMLSelectElement>('Advance to the next step').options, (o) => o.text)

    expect(options).toEqual(['+', 'prd', 'spec'])
  })

  // spec-00001-AC-10.3
  it('says there is no next step and stays disabled when the flow declares none', () => {
    renderToolbar({ nextSteps: [] })
    const select = screen.getByLabelText<HTMLSelectElement>('Advance to the next step')

    expect(select.disabled).toBe(true)
    expect(select.options[0]!.text).toBe('no next step')
  })

  it('reports the chosen next step', async () => {
    const props = renderToolbar()
    await userEvent.selectOptions(screen.getByLabelText('Advance to the next step'), 'spec')
    expect(props.onAdvance).toHaveBeenCalledWith('spec')
  })
})

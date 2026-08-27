// @vitest-environment jsdom
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { DocNode } from '../../src/docRepository.ts'
import { TooltipProvider } from '@/components/ui/tooltip'
import { CommandPalette } from '../src/CommandPalette.tsx'
import { ThemeMenu } from '../src/ThemeMenu.tsx'
import { Toolbar, type ToolbarProps } from '../src/Toolbar.tsx'

/**
 * design-00002 §6: these behaviours are Radix's to provide, but the design says
 * each must be exercised here rather than taken on trust.
 */

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

afterEach(cleanup)

// The clarify dialog is gone — clarify is one press now (design-00002 §3, round
// 8) — so the command palette is the board's one dialog, and it carries these.
describe('dialogs', () => {
  it('keeps focus inside the open dialog', async () => {
    render(<CommandPalette nodes={[NODE]} open onOpenChange={vi.fn()} onPick={vi.fn()} />)

    await userEvent.tab()
    await userEvent.tab()

    expect(screen.getByRole('dialog').contains(document.activeElement)).toBe(true)
  })

  it('closes the command palette on Escape', async () => {
    const onOpenChange = vi.fn()
    render(<CommandPalette nodes={[NODE]} open onOpenChange={onOpenChange} onPick={vi.fn()} />)

    await userEvent.keyboard('{Escape}')

    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false))
  })
})

describe('menus', () => {
  it('moves through items with the arrow keys', async () => {
    renderToolbar()

    await userEvent.click(screen.getByLabelText('Change status'))
    await userEvent.keyboard('{ArrowDown}')

    expect(document.activeElement?.textContent).toBe('active')

    await userEvent.keyboard('{ArrowDown}')
    expect(document.activeElement?.textContent).toBe('archived')
  })

  it('jumps to the last item with End and back with Home', async () => {
    renderToolbar()
    await userEvent.click(screen.getByLabelText('Change status'))

    await userEvent.keyboard('{End}')
    expect(document.activeElement?.textContent).toBe('archived')

    await userEvent.keyboard('{Home}')
    expect(document.activeElement?.textContent).toBe('active')
  })

  it('runs the focused item on Enter', async () => {
    const props = renderToolbar()

    await userEvent.click(screen.getByLabelText('Change status'))
    await userEvent.keyboard('{ArrowDown}{Enter}')

    expect(props.onStatus).toHaveBeenCalledWith('active')
  })

  it('returns focus to the trigger when the menu closes', async () => {
    renderToolbar()
    const trigger = screen.getByLabelText('Change status')
    await userEvent.click(trigger)

    await userEvent.keyboard('{Escape}')

    await waitFor(() => expect(document.activeElement).toBe(trigger))
  })

  it('gives the theme menu the same keyboard treatment', async () => {
    const onChoose = vi.fn()
    render(<ThemeMenu theme="system" onChoose={onChoose} />)

    await userEvent.click(screen.getByLabelText('Theme: System'))
    await userEvent.keyboard('{ArrowDown}{Enter}')

    expect(onChoose).toHaveBeenCalledWith('light')
  })
})

describe('icon buttons', () => {
  it('every button carries an accessible name', () => {
    renderToolbar()

    const unnamed = screen
      .getAllByRole('button')
      .filter((button) => (button.getAttribute('aria-label') ?? button.textContent ?? '').trim() === '')

    expect(unnamed).toEqual([])
  })
})

// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AnnotateArea, ISSUE_INELIGIBLE, type AnnotateAreaProps } from '../src/AnnotateArea.tsx'
import type { Selected } from '../src/annotationSelection.ts'

/** What either side hands over for the sentence being annotated. */
const SELECTED: Selected = {
  anchor: { selected: 'the anchor', before: 'the sentence that carries ', after: '.\n' },
  range: { start: 70, end: 80 },
}

function area(props: Partial<AnnotateAreaProps> = {}) {
  const onAdd = vi.fn<AnnotateAreaProps['onAdd']>().mockResolvedValue(true)
  const onReanchor = vi.fn<AnnotateAreaProps['onReanchor']>().mockResolvedValue(true)
  const onLeaveLocate = vi.fn()
  render(
    <AnnotateArea
      eligible={{ question: true, issue: true }}
      read={() => SELECTED}
      onAdd={onAdd}
      onReanchor={onReanchor}
      onLeaveLocate={onLeaveLocate}
      {...props}
    >
      <p data-testid="body">the sentence that carries the anchor.</p>
    </AnnotateArea>,
  )
  return { onAdd, onReanchor, onLeaveLocate, body: screen.getByTestId('body') }
}

/** Finish a selection the way a mouse drag does, then ask for the context menu. */
function rightClick(body: HTMLElement): boolean {
  fireEvent.mouseUp(body)
  return fireEvent.contextMenu(body.parentElement!)
}

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('taking the right-click over', () => {
  // spec-00007-AC-4.3, spec-00007-AC-4.4 — both types are selectable
  it('offers both types when both gates are open', async () => {
    const { body } = area()

    expect(rightClick(body)).toBe(false)

    expect(await screen.findByRole('menuitem', { name: 'Add a question annotation' })).toBeTruthy()
    const issue = screen.getByRole('menuitem', { name: 'Add an issue annotation' })
    expect(issue.getAttribute('data-disabled')).toBeNull()
    expect(screen.queryByText(ISSUE_INELIGIBLE)).toBeNull()
  })

  /**
   * spec-00007-AC-1.5 — with nothing selected the event is **left alone**: the
   * browser's own menu appears, and the reader keeps the copy they already had.
   * The assertion is that no application menu is there, which is how
   * design-00002 §16.2 says this is satisfied — not «a menu with the entry
   * missing», a DOM this design never produces.
   */
  it('leaves the event alone with nothing selected', () => {
    const { body } = area({ read: () => undefined })

    // Not cancelled: the native menu is what appears.
    expect(rightClick(body)).toBe(true)
    expect(screen.queryByRole('menu')).toBeNull()
  })

  /**
   * spec-00007-AC-1.6 — a selection in the front matter or inside a fenced code
   * block is not mappable, and an unmappable selection reaches here as nothing at
   * all. That the two readers refuse those two regions on **both** sides is
   * pinned in annotationMapping.test.tsx, so one rule is held by two
   * implementations.
   */
  it('leaves the event alone over an unannotatable region', () => {
    const { body } = area({ read: () => undefined })

    expect(rightClick(body)).toBe(true)
    expect(screen.queryByRole('menuitem', { name: /Add a/ })).toBeNull()
  })

  /**
   * spec-00007-AC-4.1, spec-00007-AC-4.5 — the status gate: the issue entry is
   * there and out of reach, with the reason beside it, because the owner can
   * change that status and hiding the entry would leave only the puzzle.
   */
  it('shows the issue entry disabled with its reason when the status gate is shut', async () => {
    const { body } = area({ eligible: { question: true, issue: false } })

    rightClick(body)

    const issue = await screen.findByRole('menuitem', { name: 'Add an issue annotation' })
    expect(issue.getAttribute('data-disabled')).toBe('')
    expect(screen.getByText(ISSUE_INELIGIBLE)).toBeTruthy()
    expect(screen.getByRole('menuitem', { name: 'Add a question annotation' })).toBeTruthy()
  })

  /**
   * spec-00007-AC-10.5 — the configuration gate: no agent declares a headless
   * form, so the question entry is **not there**. It is not something the owner
   * can act on here, which is why this one is hidden and the status gate's is not.
   */
  it('draws no question entry with no headless agent', async () => {
    const { body } = area({ eligible: { question: false, issue: true } })

    rightClick(body)

    expect(await screen.findByRole('menuitem', { name: 'Add an issue annotation' })).toBeTruthy()
    expect(screen.queryByRole('menuitem', { name: 'Add a question annotation' })).toBeNull()
  })

  /**
   * spec-00007-AC-4.6 — an anomalous document offers neither type, so there is
   * nothing to open a menu for and the event is left alone.
   */
  it('offers nothing at all when both gates are shut', () => {
    const { body } = area({ eligible: { question: false, issue: false } })

    expect(rightClick(body)).toBe(true)
    expect(screen.queryByRole('menu')).toBeNull()
  })
})

describe('writing the annotation', () => {
  /**
   * spec-00007-AC-1.1 — the confirmation records the type, the text and the
   * anchor the side handed over: the selected text and the context on either
   * side of it.
   */
  it('records the type, the text and the anchor', async () => {
    const { body, onAdd } = area()
    rightClick(body)
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Add a question annotation' }))

    const input = await screen.findByLabelText('Annotation text')
    fireEvent.change(input, { target: { value: 'is this still true?' } })
    await userEvent.click(screen.getByRole('button', { name: 'Annotate' }))

    expect(onAdd).toHaveBeenCalledWith({
      type: 'question',
      text: 'is this still true?',
      anchor: SELECTED.anchor,
      range: SELECTED.range,
    })
    // The input has put itself away.
    expect(screen.queryByLabelText('Annotation text')).toBeNull()
  })

  /** spec-00007-AC-1.4 — an empty annotation text is refused before it is sent. */
  it('refuses an empty annotation text', async () => {
    const { body, onAdd } = area()
    rightClick(body)
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Add a question annotation' }))

    const confirm = await screen.findByRole('button', { name: 'Annotate' })
    expect((confirm as HTMLButtonElement).disabled).toBe(true)
    // Whitespace is no text either.
    fireEvent.change(screen.getByLabelText('Annotation text'), { target: { value: '   ' } })
    expect((screen.getByRole('button', { name: 'Annotate' }) as HTMLButtonElement).disabled).toBe(true)
    expect(onAdd).not.toHaveBeenCalled()
  })

  /**
   * design-00002 §16.2 — the type may be changed where it stands, under the same
   * gate the menu was under: the entry the menu was opened from is only the
   * preset.
   */
  it('changes the type in the input, under the same gate', async () => {
    const { body, onAdd } = area()
    rightClick(body)
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Add a question annotation' }))
    fireEvent.change(await screen.findByLabelText('Annotation text'), { target: { value: 'rewrite this' } })

    await userEvent.click(screen.getByRole('radio', { name: 'issue' }))
    await userEvent.click(screen.getByRole('button', { name: 'Annotate' }))

    expect(onAdd).toHaveBeenCalledWith(expect.objectContaining({ type: 'issue', text: 'rewrite this' }))
  })

  /**
   * design-00002 §16.2's one exception to «the draft belongs to this selection»:
   * a refused confirmation keeps every word that was typed, because words thrown
   * away on a refusal are words typed twice.
   */
  it('keeps the words when the confirmation is refused', async () => {
    const onAdd = vi.fn<AnnotateAreaProps['onAdd']>().mockResolvedValue(false)
    const { body } = area({ onAdd })
    rightClick(body)
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Add a question annotation' }))
    fireEvent.change(await screen.findByLabelText('Annotation text'), { target: { value: 'is this still true?' } })

    await userEvent.click(screen.getByRole('button', { name: 'Annotate' }))

    expect(onAdd).toHaveBeenCalledTimes(1)
    expect(screen.getByLabelText<HTMLTextAreaElement>('Annotation text').value).toBe('is this still true?')
  })

  /**
   * The type is a choice of two, never of none: pressing the one already chosen
   * leaves it chosen rather than clearing the annotation's type.
   */
  it('keeps the type when the chosen one is pressed again', async () => {
    const { body, onAdd } = area()
    rightClick(body)
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Add a question annotation' }))
    fireEvent.change(await screen.findByLabelText('Annotation text'), { target: { value: 'is this still true?' } })

    await userEvent.click(screen.getByRole('radio', { name: 'question' }))
    await userEvent.click(screen.getByRole('button', { name: 'Annotate' }))

    expect(onAdd).toHaveBeenCalledWith(expect.objectContaining({ type: 'question' }))
  })

  /**
   * The draft belongs to the selection it was opened on: with that selection gone
   * before the entry is picked, there is nothing to write an annotation about.
   */
  it('opens no input once the selection has gone', async () => {
    let selected: Selected | undefined = SELECTED
    const { body } = area({ read: () => selected })
    rightClick(body)
    const item = await screen.findByRole('menuitem', { name: 'Add a question annotation' })

    selected = undefined
    fireEvent.mouseUp(body)
    await userEvent.click(item)

    expect(screen.queryByLabelText('Annotation text')).toBeNull()
  })

  /**
   * design-00002 §16.2's «the draft belongs to this selection», with the one
   * exception written into this component: focus leaving does not drop it — the
   * menu that opened it hands focus back on its way out — while Escape does.
   */
  it('keeps the draft while focus moves and drops it on Escape', async () => {
    const { body } = area()
    rightClick(body)
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Add a question annotation' }))
    fireEvent.change(await screen.findByLabelText('Annotation text'), { target: { value: 'is this still true?' } })

    fireEvent.focusIn(body)
    expect(screen.getByLabelText<HTMLTextAreaElement>('Annotation text').value).toBe('is this still true?')

    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByLabelText('Annotation text')).toBeNull())
  })

  /** The offered types in the input follow the same two gates the menu's do. */
  it('offers only the ungated type in the input', async () => {
    const { body } = area({ eligible: { question: false, issue: true } })
    rightClick(body)
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Add an issue annotation' }))

    await screen.findByLabelText('Annotation text')
    expect(screen.queryByRole('radio', { name: 'question' })).toBeNull()
    expect(screen.getByRole('radio', { name: 'issue' })).toBeTruthy()
  })
})

describe('re-anchoring', () => {
  /**
   * spec-00007-AC-3.4 — the way out of an orphan: the mode waits, the first
   * completed selection offers itself, and confirming replaces the anchor.
   */
  it('offers the first completed selection as the new anchor', async () => {
    const { body, onReanchor } = area({ reanchor: { id: 'n-1', text: 'this sentence is wrong' } })

    fireEvent.mouseUp(body)

    expect(await screen.findByText('this sentence is wrong')).toBeTruthy()
    await userEvent.click(screen.getByRole('button', { name: 'Use this selection' }))
    expect(onReanchor).toHaveBeenCalledWith({ anchor: SELECTED.anchor, range: SELECTED.range })
  })

  /** The mode is waiting for a selection, so the right-click is not taken over. */
  it('takes no right-click while it waits', () => {
    const { body } = area({ reanchor: { id: 'n-1', text: 'this sentence is wrong' } })

    expect(rightClick(body)).toBe(true)
    expect(screen.queryByRole('menu')).toBeNull()
  })

  /** An unmappable selection is no offer: the mode goes on waiting. */
  it('offers nothing for an unmappable selection', () => {
    const { body, onReanchor } = area({
      read: () => undefined,
      reanchor: { id: 'n-1', text: 'this sentence is wrong' },
    })

    fireEvent.mouseUp(body)

    expect(screen.queryByRole('button', { name: 'Use this selection' })).toBeNull()
    expect(onReanchor).not.toHaveBeenCalled()
  })

  /** A refused replacement keeps the offer up: the selection is still the answer. */
  it('keeps the offer when the replacement is refused', async () => {
    const onReanchor = vi.fn<AnnotateAreaProps['onReanchor']>().mockResolvedValue(false)
    const { body } = area({ onReanchor, reanchor: { id: 'n-1', text: 'this sentence is wrong' } })
    fireEvent.mouseUp(body)

    await userEvent.click(await screen.findByRole('button', { name: 'Use this selection' }))

    expect(onReanchor).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('button', { name: 'Use this selection' })).toBeTruthy()
  })

  /**
   * Focus going back to the body is not a dismissal: the offer stands until it is
   * answered. Escape is what puts it away.
   */
  it('keeps the offer while focus moves and drops it on Escape', async () => {
    const { body } = area({ reanchor: { id: 'n-1', text: 'this sentence is wrong' } })
    fireEvent.mouseUp(body)
    await screen.findByRole('button', { name: 'Use this selection' })

    fireEvent.focusIn(body)
    expect(screen.getByRole('button', { name: 'Use this selection' })).toBeTruthy()

    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Use this selection' })).toBeNull())
  })

  /** Cancelling leaves the annotation as it was and the mode still waiting. */
  it('leaves the anchor alone when the offer is declined', async () => {
    const { body, onReanchor } = area({ reanchor: { id: 'n-1', text: 'this sentence is wrong' } })
    fireEvent.mouseUp(body)
    await screen.findByRole('button', { name: 'Use this selection' })

    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }))

    expect(onReanchor).not.toHaveBeenCalled()
    expect(screen.queryByRole('button', { name: 'Use this selection' })).toBeNull()
  })
})

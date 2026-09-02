// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { toast } from 'sonner'
import type { SubmitPreview } from '../src/api.ts'
import {
  AnnotationList,
  type AnnotationListProps,
  COMMIT_TOOLTIP,
  NOT_LOCATABLE,
  NO_ANNOTATIONS,
  SAVE_FIRST,
} from '../src/AnnotationList.tsx'
import { BLOCKED_TEXT, CHANGED_TEXT, HANDED_BACK_TEXT, ORPHAN_TEXT, type AnnotationRow } from '../src/annotationRows.ts'
import { TooltipProvider } from '@/components/ui/tooltip'

function row(overrides: Partial<AnnotationRow> = {}): AnnotationRow {
  return {
    id: 'n-1',
    type: 'question',
    text: 'is this still true?',
    quote: 'the sentence that carries the anchor',
    state: 'pending',
    range: { start: 70, end: 80 },
    changed: false,
    action: 'locate',
    ...overrides,
  }
}

function preview(overrides: Partial<SubmitPreview> = {}): SubmitPreview {
  return {
    questions: 1,
    issues: 0,
    willTransitionTo: null,
    issueEligible: true,
    questionEligible: true,
    ...overrides,
  }
}

function list(overrides: Partial<AnnotationListProps> = {}) {
  const spies = {
    onLocate: vi.fn(),
    onThread: vi.fn(),
    onSession: vi.fn(),
    onChange: vi.fn<AnnotationListProps['onChange']>().mockResolvedValue(true),
    onRemove: vi.fn(),
    onReanchor: vi.fn(),
    onSubmit: vi.fn(),
  }
  const tree = (props: Partial<AnnotationListProps>) => (
    <TooltipProvider>
      <AnnotationList
        rows={[row()]}
        preview={preview()}
        locatable={(one) => one.range !== undefined}
        unsaved={false}
        submitting={false}
        agents={['claude']}
        askAgents={['claude']}
        {...spies}
        {...overrides}
        {...props}
      />
    </TooltipProvider>
  )
  const { rerender } = render(tree({}))
  /** The same list drawn again with something changed — what a save that reached the board looks like here. */
  return { ...spies, relist: (props: Partial<AnnotationListProps>) => rerender(tree(props)) }
}

const rows = () => within(screen.getByRole('list', { name: 'Annotations' })).getAllByRole('listitem')

beforeEach(() => {
  vi.spyOn(toast, 'error').mockImplementation(() => 'id')
  vi.spyOn(toast, 'message').mockImplementation(() => 'id')
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('the annotation list', () => {
  /**
   * spec-00007-AC-9.1 — every row says its type, its own text, the quoted source
   * and the state it is in; spec-00007-AC-9.8 is the same reading, taken from the
   * store after a restart.
   */
  it('shows each annotation’s type, text, quote and state', () => {
    list({
      rows: [
        row({ id: 'n-1' }),
        row({ id: 'n-2', state: 'answered', action: 'thread', threadId: 't-1' }),
        row({
          id: 'n-3',
          type: 'issue',
          text: 'rewrite this paragraph',
          state: 'cowriting',
          action: 'session',
          sessionId: 's9',
        }),
      ],
    })

    expect(rows()).toHaveLength(3)
    expect(rows()[0]!.textContent).toContain('is this still true?')
    expect(rows()[0]!.textContent).toContain('the sentence that carries the anchor')
    expect(rows()[0]!.textContent).toContain('unsubmitted')
    expect(within(rows()[0]!).getByLabelText('question annotation')).toBeTruthy()
    expect(rows()[1]!.textContent).toContain('answered')
    expect(rows()[2]!.textContent).toContain('cowriting')
    expect(within(rows()[2]!).getByLabelText('issue annotation')).toBeTruthy()
  })

  /** spec-00007-AC-9.9 — nothing annotated yet, and the entries are still here. */
  it('shows an empty state and keeps the submit entry', () => {
    list({ rows: [], preview: preview({ questions: 0 }) })

    expect(screen.getByText(NO_ANNOTATIONS)).toBeTruthy()
    expect(screen.queryByRole('list', { name: 'Annotations' })).toBeNull()
    expect(screen.getByRole('button', { name: /Submit/ })).toBeTruthy()
  })

  /** spec-00007-AC-9.14 — a stopped call reads terminated, mirrored from its thread. */
  it('shows a stopped question as terminated', () => {
    list({ rows: [row({ state: 'terminated', action: 'thread', threadId: 't-1' })] })
    expect(rows()[0]!.textContent).toContain('terminated')
  })

  /**
   * spec-00007-AC-9.4 — the collapse commit of a finished batch, shortened here
   * and not by the server, with a fixed explanation and nothing clickable: the
   * board has no commit view to go to.
   */
  it('shows a finished batch’s commit as a short hash', async () => {
    list({
      rows: [
        row({ id: 'n-3', type: 'issue', state: 'done', action: 'locate', commit: '0f1e2d3c4b5a69788796' }),
      ],
    })

    const hash = screen.getByText('0f1e2d3')
    expect(hash.tagName).not.toBe('BUTTON')
    await userEvent.hover(hash)
    expect((await screen.findAllByText(COMMIT_TOOLTIP)).length).toBeGreaterThan(0)
  })

  /** spec-00007-AC-9.5 — a batch that landed no change says so. */
  it('says a finished batch landed no change', () => {
    list({ rows: [row({ id: 'n-3', type: 'issue', state: 'done', action: 'locate', commit: null })] })
    expect(screen.getByText('no change')).toBeTruthy()
  })

  /**
   * design-00002 §16.4 — the orphan: the reason in the destructive colour beside
   * the `Unlink` icon, the quote opened out in full because it is the only clue
   * left, the locate entry out with its reason, and the re-anchor promoted since
   * that is the way out.
   */
  it('marks an orphan and promotes the way out of it', async () => {
    const spies = list({ rows: [row({ orphan: 'missing', range: undefined, action: 'none' })] })

    expect(screen.getByText(ORPHAN_TEXT.missing)).toBeTruthy()
    expect(screen.getByText('the sentence that carries the anchor').className).not.toContain('line-clamp-2')
    const locate = screen.getByRole<HTMLButtonElement>('button', { name: 'Locate n-1' })
    expect(locate.disabled).toBe(true)
    await userEvent.hover(locate.parentElement!)
    expect((await screen.findAllByText(NOT_LOCATABLE)).length).toBeGreaterThan(0)
    // «No action» is really nothing: no toast, no navigation.
    await userEvent.click(within(rows()[0]!).getAllByRole('button')[0]!)
    expect(spies.onLocate).not.toHaveBeenCalled()
    expect(toast.error).not.toHaveBeenCalled()
  })

  /** design-00002 §16.4 — the other reason: `ambiguous` gets its own sentence. */
  it('says when the source text now stands in several places', () => {
    list({ rows: [row({ orphan: 'ambiguous', range: undefined, action: 'none' })] })
    expect(screen.getByText(ORPHAN_TEXT.ambiguous)).toBeTruthy()
  })

  /**
   * spec-00007-AC-12.1 … AC-12.3 — a submitted annotation whose anchor no longer
   * lands: the same icon in the muted colour, one line, the state badge untouched
   * and the quote still there. A document that has moved on is not an error.
   */
  it('degrades a submitted annotation’s locate without changing its state', () => {
    list({
      rows: [
        row({ id: 'n-2', state: 'answered', action: 'thread', changed: true, range: undefined, threadId: 't-1' }),
        row({ id: 'n-3', type: 'issue', state: 'done', action: 'none', changed: true, commit: 'abc1234' }),
      ],
    })

    expect(screen.getAllByText(CHANGED_TEXT)).toHaveLength(2)
    expect(rows()[0]!.textContent).toContain('answered')
    expect(rows()[0]!.textContent).toContain('the sentence that carries the anchor')
    expect(rows()[1]!.textContent).toContain('done')
    expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Locate n-2' }).disabled).toBe(true)
    expect(screen.queryByText(ORPHAN_TEXT.missing)).toBeNull()
  })

  /**
   * design-00002 §16.5 — a held-back annotation keeps its reason on its own row:
   * the toast goes away and the annotation still has to be dealt with.
   */
  it('keeps a held-back annotation’s reason on its row', () => {
    list({ rows: [row({ id: 'n-3', type: 'issue', blocked: 'cap-reached' })] })
    expect(screen.getByText(BLOCKED_TEXT['cap-reached'])).toBeTruthy()
  })

  /**
   * design-00002 §16.6 — a batch that was stopped hands its annotations back, and
   * the row says so rather than pretending nothing happened.
   */
  it('says the last cowrite was stopped on the rows it handed back', () => {
    list({ rows: [row({ id: 'n-3', type: 'issue', handedBack: 'terminated' })] })
    expect(screen.getByText(HANDED_BACK_TEXT.terminated)).toBeTruthy()
    expect(rows()[0]!.textContent).toContain('unsubmitted')
  })
})

describe('the row’s own dispatch', () => {
  /** design-00002 §16.4's table, the four outcomes it has. */
  it('locates an unsubmitted row that can be located', async () => {
    const spies = list()
    await userEvent.click(within(rows()[0]!).getAllByRole('button')[0]!)
    expect(spies.onLocate).toHaveBeenCalledWith(expect.objectContaining({ id: 'n-1' }))
  })

  // spec-00007-AC-9.2 — the question path's navigation, whatever state the thread is in
  it('goes to the thread of a submitted question', async () => {
    const spies = list({ rows: [row({ state: 'answered', action: 'thread', threadId: 't-1' })] })
    await userEvent.click(within(rows()[0]!).getAllByRole('button')[0]!)
    expect(spies.onThread).toHaveBeenCalledWith(expect.objectContaining({ threadId: 't-1' }))
  })

  // spec-00007-AC-9.3 — a batch being cowritten leads to its session
  it('shows the session of a batch being cowritten', async () => {
    const spies = list({
      rows: [row({ id: 'n-3', type: 'issue', state: 'cowriting', action: 'session', sessionId: 's9' })],
    })
    await userEvent.click(within(rows()[0]!).getAllByRole('button')[0]!)
    expect(spies.onSession).toHaveBeenCalledWith(expect.objectContaining({ sessionId: 's9' }))
  })
})

describe('changing an annotation before it goes', () => {
  /** spec-00007-FR-3 — the text and the type may be changed where the row is. */
  it('edits the text and the type in place', async () => {
    const spies = list()

    await userEvent.click(screen.getByRole('button', { name: 'Edit n-1' }))
    fireEvent.change(await screen.findByLabelText('Text of n-1'), { target: { value: 'rewrite this' } })
    await userEvent.click(screen.getByRole('radio', { name: 'issue' }))
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(spies.onChange).toHaveBeenCalledWith('n-1', { text: 'rewrite this', type: 'issue' })
    expect(screen.queryByLabelText('Text of n-1')).toBeNull()
  })

  /** An empty text is refused here for the same reason it is at the entry. */
  it('refuses to save an empty text', async () => {
    const spies = list()
    await userEvent.click(screen.getByRole('button', { name: 'Edit n-1' }))
    fireEvent.change(await screen.findByLabelText('Text of n-1'), { target: { value: '' } })

    expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Save' }).disabled).toBe(true)
    expect(spies.onChange).not.toHaveBeenCalled()
  })

  /** spec-00007-AC-10.5 — with no headless agent there is no question to move to. */
  it('offers no move to question with no headless agent', async () => {
    list({ askAgents: [] })
    await userEvent.click(screen.getByRole('button', { name: 'Edit n-1' }))

    await screen.findByLabelText('Text of n-1')
    expect(screen.queryByRole('radio', { name: 'question' })).toBeNull()
  })

  /** spec-00007-FR-3 — dropping one, and entering the re-anchor mode on one. */
  it('drops an annotation and enters the re-anchor mode', async () => {
    const spies = list()

    await userEvent.click(screen.getByRole('button', { name: 'Re-anchor n-1' }))
    expect(spies.onReanchor).toHaveBeenCalledWith('n-1')

    await userEvent.click(screen.getByRole('button', { name: 'Delete n-1' }))
    expect(spies.onRemove).toHaveBeenCalledWith('n-1')
  })

  /** A submitted annotation takes no change, so it carries none of the three entries. */
  it('offers no change on a submitted annotation', () => {
    list({ rows: [row({ state: 'answered', action: 'thread', threadId: 't-1' })] })

    expect(screen.queryByRole('button', { name: 'Edit n-1' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Re-anchor n-1' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Delete n-1' })).toBeNull()
    expect(screen.getByRole('button', { name: 'Locate n-1' })).toBeTruthy()
  })
})

describe('the unified submit entry', () => {
  /** spec-00007-AC-5.3 — nothing unsubmitted is the one ground the entry is out on. */
  it('is out only when there is nothing unsubmitted', () => {
    list({ rows: [], preview: preview({ questions: 0 }) })
    expect(screen.getByRole<HTMLButtonElement>('button', { name: /Submit/ }).disabled).toBe(true)

    cleanup()
    list()
    expect(screen.getByRole<HTMLButtonElement>('button', { name: /Submit/ }).disabled).toBe(false)
  })

  /**
   * spec-00007-AC-5.4 — an unsaved buffer does **not** disable the entry: the
   * refusal is what has to be observable, and a disabled entry would leave that
   * execution nowhere to happen. So it is pressed, and refused with the way out.
   */
  it('refuses the press while the buffer is unsaved', async () => {
    const spies = list({ unsaved: true })

    await userEvent.click(screen.getByRole('button', { name: /Submit/ }))

    expect(toast.error).toHaveBeenCalledWith(SAVE_FIRST)
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(spies.onSubmit).not.toHaveBeenCalled()
  })

  /**
   * spec-00007-AC-5.7 — the three lines, each on its own field of the submit
   * statement and none of them counted here: one ask thread, one cowrite session,
   * and the document moving to `draft`.
   */
  it('states what the submit will do, line by line', async () => {
    list({
      rows: [row(), row({ id: 'n-2', type: 'issue' }), row({ id: 'n-3', type: 'issue' })],
      preview: preview({ questions: 1, issues: 2, willTransitionTo: 'draft' }),
    })

    await userEvent.click(screen.getByRole('button', { name: /Submit/ }))

    const dialog = await screen.findByRole('dialog')
    expect(dialog.textContent).toContain('will start 1 ask thread(s)')
    expect(dialog.textContent).toContain('will start one cowrite session')
    expect(dialog.textContent).toContain('will move this document to draft')
  })

  /** The lines are conditional: no issue means no cowrite and no revision round. */
  it('leaves out the lines that do not apply', async () => {
    list({ preview: preview({ questions: 2, issues: 0, willTransitionTo: null }) })

    await userEvent.click(screen.getByRole('button', { name: /Submit/ }))

    const dialog = await screen.findByRole('dialog')
    expect(dialog.textContent).toContain('will start 2 ask thread(s)')
    expect(dialog.textContent).not.toContain('cowrite session')
    expect(dialog.textContent).not.toContain('will move this document')
  })

  /**
   * spec-00007-AC-5.5 — two agents, both headless: the choice is on show for each
   * path and each defaults to the first of its own set.
   */
  it('offers each path its agent and defaults to the first of its set', async () => {
    const spies = list({
      preview: preview({ questions: 1, issues: 1 }),
      agents: ['claude', 'codex'],
      askAgents: ['claude', 'codex'],
    })

    await userEvent.click(screen.getByRole('button', { name: /Submit/ }))
    await screen.findByRole('dialog')
    expect(screen.getByLabelText('Question agent').textContent).toContain('claude')
    expect(screen.getByLabelText('Co-write agent').textContent).toContain('claude')
    await userEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: /Submit/ }))

    expect(spies.onSubmit).toHaveBeenCalledWith({ question: 'claude', cowrite: 'claude' })
  })

  /**
   * spec-00007-AC-5.6 — only the second agent declares a headless form: the
   * question path has no choice to offer and names nobody, so the server takes
   * the first of **its** set, while the cowrite path chooses among them all.
   */
  it('names nobody for a path whose set holds one agent', async () => {
    const spies = list({
      preview: preview({ questions: 1, issues: 1 }),
      agents: ['claude', 'codex'],
      askAgents: ['codex'],
    })

    await userEvent.click(screen.getByRole('button', { name: /Submit/ }))
    const dialog = await screen.findByRole('dialog')
    expect(screen.queryByLabelText('Question agent')).toBeNull()
    await userEvent.click(within(dialog).getByRole('button', { name: /Submit/ }))

    expect(spies.onSubmit).toHaveBeenCalledWith({ cowrite: 'claude' })
  })

  /** The agent picked is the one that goes. */
  it('sends the agent that was picked', async () => {
    const spies = list({
      preview: preview({ questions: 1, issues: 1 }),
      agents: ['claude', 'codex'],
      askAgents: ['claude', 'codex'],
    })
    await userEvent.click(screen.getByRole('button', { name: /Submit/ }))
    await screen.findByRole('dialog')

    await userEvent.click(screen.getByLabelText('Co-write agent'))
    await userEvent.click(await screen.findByRole('menuitem', { name: 'codex' }))
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: /Submit/ }))

    expect(spies.onSubmit).toHaveBeenCalledWith({ question: 'claude', cowrite: 'codex' })
  })

  // spec-00009-FR-8 — the settings panel took the picked agent off the list while
  // the statement was open. A name the list no longer carries would come back a 422,
  // so each of the two picks falls back to the first of its own set (design-00002 §18.3).
  it('falls back to the first agent once the picked one leaves the list', async () => {
    const spies = list({
      preview: preview({ questions: 1, issues: 1 }),
      agents: ['claude', 'codex'],
      askAgents: ['claude', 'codex'],
    })
    await userEvent.click(screen.getByRole('button', { name: /Submit/ }))
    await screen.findByRole('dialog')
    await userEvent.click(screen.getByLabelText('Question agent'))
    await userEvent.click(await screen.findByRole('menuitem', { name: 'codex' }))
    await userEvent.click(screen.getByLabelText('Co-write agent'))
    await userEvent.click(await screen.findByRole('menuitem', { name: 'codex' }))
    expect(screen.getByLabelText('Question agent').textContent).toContain('codex')

    spies.relist({ agents: ['claude', 'gemini'], askAgents: ['claude', 'gemini'] })
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: /Submit/ }))

    expect(spies.onSubmit).toHaveBeenCalledWith({ question: 'claude', cowrite: 'claude' })
  })

  /** Nothing is submitted by opening the statement: it is a confirmation. */
  it('submits nothing until the statement is confirmed', async () => {
    const spies = list()

    await userEvent.click(screen.getByRole('button', { name: /Submit/ }))
    const dialog = await screen.findByRole('dialog')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))

    expect(spies.onSubmit).not.toHaveBeenCalled()
  })

  /** spec-00007-AC-10.4's first guard: an entry that is on its way out takes no second press. */
  it('is out while a submit is on its way', () => {
    list({ submitting: true })
    expect(screen.getByRole<HTMLButtonElement>('button', { name: /Submit/ }).disabled).toBe(true)
  })
})

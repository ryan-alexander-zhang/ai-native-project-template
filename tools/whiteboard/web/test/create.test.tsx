// @vitest-environment jsdom
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { toast } from 'sonner'
import type { DocGraph, DocNode } from '../../src/docRepository.ts'
import { Board } from '../src/Board.tsx'
import { Editor } from '../src/Editor.tsx'
import { ApiError, type ConfigPayload, api } from '../src/api.ts'
import { isSlug, prefillFrontMatter } from '../src/frontMatter.ts'

const TEMPLATE = `---
id: idea-00001-example-slug
type: idea
status: draft|active|archived
---

## Open Questions

- <what is unknown, and what would close it>
`

function node(overrides: Partial<DocNode> = {}): DocNode {
  return {
    id: 'idea-00001-x',
    path: 'idea/a.md',
    type: 'idea',
    status: 'draft',
    title: 'Whiteboard idea',
    relations: {},
    ok: true,
    problems: [],
    ...overrides,
  }
}

const GRAPH: DocGraph = { nodes: [node()], edges: [], issues: [], diagnostics: [] }

function config(entry: string[]): ConfigPayload {
  return {
    types: { idea: 'living', prd: 'living', spec: 'living' },
    relations: ['parent'],
    flow: {},
    focus: {},
    agents: [{ name: 'claude', command: 'claude', args: [] }],
    entry,
    clarifiable: [],
    auditable: [],
  }
}

function serve(entry: string[]) {
  vi.spyOn(api, 'graph').mockResolvedValue(GRAPH)
  vi.spyOn(api, 'transitions').mockResolvedValue(['active'])
  vi.spyOn(api, 'nextSteps').mockResolvedValue([])
  vi.spyOn(api, 'session').mockResolvedValue({ current: null })
  vi.spyOn(api, 'config').mockResolvedValue(config(entry))
  vi.spyOn(toast, 'success').mockImplementation(() => 'id')
  vi.spyOn(toast, 'error').mockImplementation(() => 'id')
}

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

/** Open the board with a create entry, and open its dialog. */
async function openDialog(entry = ['idea', 'prd']) {
  serve(entry)
  render(<Board />)
  await waitFor(() => expect(screen.getByRole('button', { name: 'New' })).toBeTruthy())
  await userEvent.click(screen.getByRole('button', { name: 'New' }))
  return await screen.findByRole('dialog')
}

describe('the create entry', () => {
  // spec-00001-AC-53.6 — a flow that declares no entry type has no starting
  // point to offer, so the entry is not drawn at all
  it('is not drawn when the config declares no entry type', async () => {
    serve([])
    render(<Board />)
    await waitFor(() => expect(screen.getByTestId('node-idea-00001-x')).toBeTruthy())

    expect(screen.queryByRole('button', { name: 'New' })).toBeNull()
  })

  it('is drawn once the config declares one', async () => {
    serve(['idea'])
    render(<Board />)

    await waitFor(() => expect(screen.getByRole('button', { name: 'New' })).toBeTruthy())
  })

  // spec-00001-AC-53.2 at the entry: the types that may be created are the
  // config's `entry`, and a type it does not name is not on offer — spec is a
  // declared type here, and still absent
  it('offers only the types the config declares as entries', async () => {
    await openDialog(['idea', 'prd'])

    await userEvent.click(screen.getByLabelText('Document type'))

    const offered = (await screen.findAllByRole('menuitem')).map((item) => item.textContent)
    expect(offered).toEqual(['idea', 'prd'])
  })

  // spec-00001-AC-53.4 — the slug is refused before a doomed request is sent
  it('refuses a slug that is not lowercase and hyphenated', async () => {
    await openDialog()

    await userEvent.type(screen.getByLabelText('Slug'), 'Not A Slug')

    expect(await screen.findByText('a slug is lowercase words joined by hyphens')).toBeTruthy()
    expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Create' }).disabled).toBe(true)
  })

  it('waits for a slug before it will create anything', async () => {
    await openDialog()

    expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Create' }).disabled).toBe(true)
    expect(screen.queryByText('a slug is lowercase words joined by hyphens')).toBeNull()
  })

  // spec-00001-AC-53.1, first half: the number is the server's, the slug is the
  // user's, and what opens is the template with both in its front matter
  it('opens the buffer prefilled with the allocated id and a draft status', async () => {
    const prefill = vi
      .spyOn(api, 'createPrefill')
      .mockResolvedValue({ idPrefix: 'idea-00002-', template: TEMPLATE })
    await openDialog()

    await userEvent.type(screen.getByLabelText('Slug'), 'whiteboard-notes')
    await userEvent.click(screen.getByRole('button', { name: 'Create' }))

    expect(prefill).toHaveBeenCalledWith('idea')
    await waitFor(() =>
      expect(screen.getByTestId('editor-host').textContent).toContain('id: idea-00002-whiteboard-notes'),
    )
    expect(screen.getByTestId('editor-host').textContent).toContain('status: draft')
    expect(screen.getByTestId('editor-host').textContent).not.toContain('draft|active|archived')
  })

  // spec-00001-AC-53.1, second half: nothing is written until the save, and the
  // save is a create — the board then takes the new document in and selects it
  it('creates the document on save and takes the new node in', async () => {
    vi.spyOn(api, 'createPrefill').mockResolvedValue({ idPrefix: 'idea-00002-', template: TEMPLATE })
    const created = vi.spyOn(api, 'createDoc').mockResolvedValue({ committed: true })
    const save = vi.spyOn(api, 'save')
    await openDialog()
    await userEvent.type(screen.getByLabelText('Slug'), 'whiteboard-notes')
    await userEvent.click(screen.getByRole('button', { name: 'Create' }))
    await waitFor(() => expect(screen.getByTestId('editor-host')).toBeTruthy())
    // The document exists from the save on, so the graph the board re-reads has it.
    const withNew = { ...GRAPH, nodes: [...GRAPH.nodes, node({ id: 'idea-00002-whiteboard-notes' })] }
    vi.spyOn(api, 'graph').mockResolvedValue(withNew)

    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(created).toHaveBeenCalledTimes(1))
    expect(created.mock.calls[0]![0]).toBe('idea-00002-whiteboard-notes')
    expect(created.mock.calls[0]![1]).toContain('id: idea-00002-whiteboard-notes')
    // A create never goes down the revise path: there is no version to be
    // in conflict with (design-00001 §7).
    expect(save).not.toHaveBeenCalled()
    await waitFor(() => expect(screen.getByTestId('node-idea-00002-whiteboard-notes')).toBeTruthy())
    await waitFor(() =>
      expect(screen.getByRole('toolbar', { name: 'Actions for idea-00002-whiteboard-notes' })).toBeTruthy(),
    )
  })

  // Typing a slug and pressing Enter is the whole dialog; reaching for the
  // button is optional.
  it('creates on Enter in the slug field', async () => {
    const prefill = vi
      .spyOn(api, 'createPrefill')
      .mockResolvedValue({ idPrefix: 'idea-00002-', template: TEMPLATE })
    await openDialog()

    await userEvent.type(screen.getByLabelText('Slug'), 'whiteboard-notes{Enter}')

    expect(prefill).toHaveBeenCalledWith('idea')
  })

  it('does not act on Enter while the slug is not a slug', async () => {
    const prefill = vi
      .spyOn(api, 'createPrefill')
      .mockResolvedValue({ idPrefix: 'idea-00002-', template: TEMPLATE })
    await openDialog()

    await userEvent.type(screen.getByLabelText('Slug'), 'Not A Slug{Enter}')

    expect(prefill).not.toHaveBeenCalled()
  })

  it('says so when the prefill is refused and opens no buffer', async () => {
    vi.spyOn(api, 'createPrefill').mockRejectedValue(new ApiError(422, 'spec is not a flow entry type'))
    await openDialog()

    await userEvent.type(screen.getByLabelText('Slug'), 'whiteboard-notes')
    await userEvent.click(screen.getByRole('button', { name: 'Create' }))

    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('spec is not a flow entry type'))
    expect(screen.queryByTestId('editor-host')).toBeNull()
  })
})

describe('a prefilled buffer', () => {
  beforeEach(() => {
    vi.spyOn(toast, 'success').mockImplementation(() => 'id')
    vi.spyOn(toast, 'error').mockImplementation(() => 'id')
  })

  it('reads nothing off disk — there is nothing there yet', async () => {
    const read = vi.spyOn(api, 'doc')
    render(<Editor docId="idea-00002-x" draft={TEMPLATE} onSaved={vi.fn()} onClose={vi.fn()} />)

    await waitFor(() => expect(screen.getByTestId('editor-host').textContent).toContain('id: idea-00001'))
    expect(read).not.toHaveBeenCalled()
  })

  it('creates on save and says which document it created', async () => {
    const created = vi.spyOn(api, 'createDoc').mockResolvedValue({ committed: true })
    const onSaved = vi.fn()
    render(<Editor docId="idea-00002-x" draft={TEMPLATE} onSaved={onSaved} onClose={vi.fn()} />)
    await waitFor(() => expect(screen.getByTestId('editor-host').textContent).toContain('id: idea-00001'))

    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(created).toHaveBeenCalledWith('idea-00002-x', TEMPLATE)
    await waitFor(() => expect(toast.success).toHaveBeenCalledWith('created idea-00002-x'))
    expect(onSaved).toHaveBeenCalled()
  })

  // spec-00001-AC-53.3 as the user sees it: the id is taken, and nothing is
  // overwritten — the way out is another slug, so the toast says so
  it('surfaces a taken id as a refusal to overwrite', async () => {
    vi.spyOn(api, 'createDoc').mockRejectedValue(
      new ApiError(409, 'idea-00002-x already exists; refresh the board'),
    )
    render(<Editor docId="idea-00002-x" draft={TEMPLATE} onSaved={vi.fn()} onClose={vi.fn()} />)
    await waitFor(() => expect(screen.getByTestId('editor-host').textContent).toContain('id: idea-00001'))

    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() =>
      expect(toast.error).toHaveBeenCalledWith('idea-00002-x already exists; refresh the board', {
        description: 'pick another slug',
      }),
    )
  })
})

// rule-00001-BR-18 as the front end reads it
describe('a slug', () => {
  it('is lowercase words joined by single hyphens', () => {
    expect(isSlug('whiteboard-notes')).toBe(true)
    expect(isSlug('round-11')).toBe(true)
    expect(isSlug('notes')).toBe(true)
  })

  it('is nothing else', () => {
    for (const rejected of ['Notes', 'two words', 'trailing-', '-leading', 'double--hyphen', '', 'under_score']) {
      expect(isSlug(rejected)).toBe(false)
    }
  })
})

describe('the prefill', () => {
  it('carries the allocated id, the type, and a draft status', () => {
    const filled = prefillFrontMatter(TEMPLATE, 'idea-00002-notes', 'idea')

    expect(filled).toContain('id: idea-00002-notes')
    expect(filled).toContain('type: idea')
    expect(filled).toContain('status: draft\n')
    expect(filled).toContain('## Open Questions')
  })

  it('leaves the body alone, front matter lines and all', () => {
    const body = `${TEMPLATE}\nstatus: draft|active|archived is what the template says\n`

    expect(prefillFrontMatter(body, 'idea-00002-notes', 'idea')).toContain(
      'status: draft|active|archived is what the template says',
    )
  })

  it('hands back a template with no front matter untouched', () => {
    expect(prefillFrontMatter('# just a body\n', 'idea-00002-notes', 'idea')).toBe('# just a body\n')
  })
})

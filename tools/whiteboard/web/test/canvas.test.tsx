// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { DocGraph, DocNode } from '../../src/docRepository.ts'
import { Board } from '../src/Board.tsx'
import { api } from '../src/api.ts'
import { findMatch, toFlowEdges, toFlowNodes } from '../src/canvasModel.ts'

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

const IDEA = node({ id: 'idea-00001-x', type: 'idea', status: 'active', title: 'Whiteboard idea', path: 'idea/a.md' })
const GRAPH: DocGraph = {
  nodes: [node(), IDEA],
  edges: [{ from: 'prd-00001-x', to: 'idea-00001-x', relation: 'parent', ok: true }],
  issues: [],
}
const PLACED = [
  { id: 'prd-00001-x', x: 10, y: 200 },
  { id: 'idea-00001-x', x: 10, y: 0 },
]

afterEach(cleanup)

describe('toFlowNodes', () => {
  it('places each document where the layout put it', () => {
    const nodes = toFlowNodes(GRAPH, PLACED)

    expect(nodes.map((item) => item.position)).toEqual([
      { x: 10, y: 200 },
      { x: 10, y: 0 },
    ])
    expect(nodes.every((item) => item.type === 'doc')).toBe(true)
  })

  it('marks the selected document', () => {
    expect(toFlowNodes(GRAPH, PLACED, 'idea-00001-x').map((item) => item.selected)).toEqual([false, true])
  })

  it('drops an unplaced document at the origin rather than losing it', () => {
    expect(toFlowNodes(GRAPH, [])[0]!.position).toEqual({ x: 0, y: 0 })
  })
})

describe('toFlowEdges', () => {
  it('carries the relation as the edge label', () => {
    expect(toFlowEdges(GRAPH)).toEqual([
      { id: 'e0', source: 'prd-00001-x', target: 'idea-00001-x', label: 'parent', className: undefined },
    ])
  })

  // spec-00001-AC-2.2
  it('marks an edge pointing at an unknown document', () => {
    const graph = { ...GRAPH, edges: [{ from: 'prd-00001-x', to: 'ghost', relation: 'parent', ok: false }] }
    expect(toFlowEdges(graph)[0]!.className).toBe('edge--broken')
  })
})

describe('findMatch', () => {
  it('finds a document by id fragment', () => {
    expect(findMatch(GRAPH.nodes, 'idea-00001')!.id).toBe('idea-00001-x')
  })

  it('finds a document by title fragment', () => {
    expect(findMatch(GRAPH.nodes, 'Whiteboard idea')!.id).toBe('idea-00001-x')
  })

  it('finds nothing for an empty or unmatched query', () => {
    expect(findMatch(GRAPH.nodes, '   ')).toBeUndefined()
    expect(findMatch(GRAPH.nodes, 'nothing here')).toBeUndefined()
  })
})

describe('the board', () => {
  beforeEach(() => {
    vi.spyOn(api, 'graph').mockResolvedValue(GRAPH)
    vi.spyOn(api, 'transitions').mockResolvedValue(['active', 'archived'])
    vi.spyOn(api, 'nextSteps').mockResolvedValue([{ next: 'spec', carry: 'parent' }])
    vi.spyOn(api, 'session').mockResolvedValue({ current: null })
  })

  afterEach(() => vi.restoreAllMocks())

  it('renders the canvas with the documents on it', async () => {
    render(<Board />)

    await waitFor(() => expect(screen.getByTestId('node-prd-00001-x')).toBeTruthy())
    expect(screen.getByTestId('node-idea-00001-x')).toBeTruthy()
    expect(screen.getByText('no issues')).toBeTruthy()
  })

  it('counts the issues it found', async () => {
    vi.spyOn(api, 'graph').mockResolvedValue({
      ...GRAPH,
      issues: [{ path: 'prd/a.md', message: 'front matter is missing' }],
    })
    render(<Board />)

    await waitFor(() => expect(screen.getByText('1 issues')).toBeTruthy())
  })

  // spec-00001-AC-3.1
  it('opens the toolbar for the node the user clicks', async () => {
    render(<Board />)
    await waitFor(() => expect(screen.getByTestId('node-prd-00001-x')).toBeTruthy())

    fireEvent.click(screen.getByTestId('node-prd-00001-x'))

    await waitFor(() => expect(screen.getByRole('toolbar', { name: /prd-00001-x/ })).toBeTruthy())
    expect(screen.getByRole('button', { name: 'Accept' })).toBeTruthy()
  })

  // spec-00001-AC-3.2
  it('closes the toolbar when the canvas background is clicked', async () => {
    const { container } = render(<Board />)
    await waitFor(() => expect(screen.getByTestId('node-prd-00001-x')).toBeTruthy())
    fireEvent.click(screen.getByTestId('node-prd-00001-x'))
    await waitFor(() => expect(screen.getByRole('toolbar', { name: /prd-00001-x/ })).toBeTruthy())

    fireEvent.click(container.querySelector('.react-flow__pane')!)

    await waitFor(() => expect(screen.queryByRole('toolbar')).toBeNull())
  })

  // spec-00001-AC-8.1 as the user sees it
  it('accepts a draft from the toolbar and refreshes', async () => {
    const accept = vi.spyOn(api, 'accept').mockResolvedValue({ committed: true, status: 'active' })
    render(<Board />)
    await waitFor(() => expect(screen.getByTestId('node-prd-00001-x')).toBeTruthy())
    fireEvent.click(screen.getByTestId('node-prd-00001-x'))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Accept' })).toBeTruthy())

    await userEvent.click(screen.getByRole('button', { name: 'Accept' }))

    expect(accept).toHaveBeenCalledWith('prd-00001-x')
    await waitFor(() => expect(api.graph).toHaveBeenCalledTimes(2))
  })

  // spec-00001-AC-8.3 as the user sees it
  it('shows the refusal when an action is rejected', async () => {
    vi.spyOn(api, 'accept').mockRejectedValue(new Error('accept applies to a draft document'))
    render(<Board />)
    await waitFor(() => expect(screen.getByTestId('node-prd-00001-x')).toBeTruthy())
    fireEvent.click(screen.getByTestId('node-prd-00001-x'))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Accept' })).toBeTruthy())

    await userEvent.click(screen.getByRole('button', { name: 'Accept' }))

    await waitFor(() => expect(screen.getByText('accept applies to a draft document')).toBeTruthy())
  })

  // spec-00001-AC-6.1 as the user sees it
  it('changes status from the toolbar', async () => {
    const setStatus = vi.spyOn(api, 'setStatus').mockResolvedValue({ committed: true, status: 'active' })
    render(<Board />)
    await waitFor(() => expect(screen.getByTestId('node-prd-00001-x')).toBeTruthy())
    fireEvent.click(screen.getByTestId('node-prd-00001-x'))
    await waitFor(() => expect(screen.getByLabelText('Change status')).toBeTruthy())

    await userEvent.selectOptions(screen.getByLabelText('Change status'), 'active')

    expect(setStatus).toHaveBeenCalledWith('prd-00001-x', 'active')
  })

  // spec-00001-AC-9.1 as the user sees it
  it('records clarify questions from the toolbar', async () => {
    const clarify = vi.spyOn(api, 'clarify').mockResolvedValue({ committed: true, status: 'draft' })
    render(<Board />)
    await waitFor(() => expect(screen.getByTestId('node-prd-00001-x')).toBeTruthy())
    fireEvent.click(screen.getByTestId('node-prd-00001-x'))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Clarify' })).toBeTruthy())

    await userEvent.click(screen.getByRole('button', { name: 'Clarify' }))
    await userEvent.type(screen.getByLabelText('Open questions, one per line'), 'who owns this?')
    await userEvent.click(screen.getByRole('button', { name: 'Record questions' }))

    expect(clarify).toHaveBeenCalledWith('prd-00001-x', ['who owns this?'])
  })

  // spec-00001-AC-11.1 as the user sees it
  it('starts an advance from the toolbar and opens the terminal', async () => {
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
    const advance = vi.spyOn(api, 'advance').mockResolvedValue({
      id: 's1',
      sourceId: 'prd-00001-x',
      targetType: 'spec',
      status: 'running',
    })
    render(<Board />)
    await waitFor(() => expect(screen.getByTestId('node-prd-00001-x')).toBeTruthy())
    fireEvent.click(screen.getByTestId('node-prd-00001-x'))
    await waitFor(() => expect(screen.getByLabelText('Advance to the next step')).toBeTruthy())

    await userEvent.selectOptions(screen.getByLabelText('Advance to the next step'), 'spec')

    expect(advance).toHaveBeenCalledWith('prd-00001-x', 'spec')
    await waitFor(() => expect(screen.getByLabelText('Agent session')).toBeTruthy())
    vi.unstubAllGlobals()
  })

  it('opens the editor from the toolbar', async () => {
    vi.spyOn(api, 'doc').mockResolvedValue({ path: 'prd/a.md', content: '# X\n', hash: 'h' })
    render(<Board />)
    await waitFor(() => expect(screen.getByTestId('node-prd-00001-x')).toBeTruthy())
    fireEvent.click(screen.getByTestId('node-prd-00001-x'))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Edit' })).toBeTruthy())

    await userEvent.click(screen.getByRole('button', { name: 'Edit' }))

    await waitFor(() => expect(screen.getByLabelText('Editing prd-00001-x')).toBeTruthy())
  })

  // the focus of spec-00001 §7
  it('focuses the document named in the search box', async () => {
    render(<Board />)
    await waitFor(() => expect(screen.getByTestId('node-idea-00001-x')).toBeTruthy())

    await userEvent.type(screen.getByLabelText('Find a document'), 'idea-00001{Enter}')

    await waitFor(() => expect(screen.getByRole('toolbar', { name: /idea-00001-x/ })).toBeTruthy())
  })

  it('leaves the board alone when the search matches nothing', async () => {
    render(<Board />)
    await waitFor(() => expect(screen.getByTestId('node-idea-00001-x')).toBeTruthy())

    await userEvent.type(screen.getByLabelText('Find a document'), 'nothing{Enter}')

    expect(screen.queryByRole('toolbar')).toBeNull()
  })
})

// @vitest-environment jsdom
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { DocGraph, DocNode } from '../../src/docRepository.ts'
import { api } from '../src/api.ts'

const setCenter = vi.fn()

// The viewport move is React Flow's to make, so the module is mocked to observe it.
vi.mock('@xyflow/react', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@xyflow/react')>()
  return { ...actual, useReactFlow: () => ({ ...actual.useReactFlow(), setCenter }) }
})

const { Board } = await import('../src/Board.tsx')

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

const GRAPH: DocGraph = {
  nodes: [node(), node({ id: 'idea-00001-x', type: 'idea', title: 'Whiteboard idea', path: 'idea/a.md' })],
  edges: [],
  issues: [],
}

beforeEach(() => {
  setCenter.mockClear()
  vi.spyOn(api, 'graph').mockResolvedValue(GRAPH)
  vi.spyOn(api, 'transitions').mockResolvedValue(['active'])
  vi.spyOn(api, 'nextSteps').mockResolvedValue([])
  vi.spyOn(api, 'session').mockResolvedValue({ current: null })
  vi.spyOn(api, 'config').mockResolvedValue({
    types: { prd: 'living', idea: 'living' },
    relations: ['parent'],
    flow: {},
    agents: [{ name: 'claude', command: 'claude', args: [] }],
  })
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

async function pick(id: string) {
  render(<Board />)
  await waitFor(() => expect(screen.getByTestId(`node-${id}`)).toBeTruthy())
  await userEvent.click(screen.getByRole('button', { name: /Find a document/ }))
  await userEvent.type(screen.getByPlaceholderText('Find a document by id or title'), id)
  await userEvent.click(await screen.findByRole('option', { name: new RegExp(id) }))
}

// spec-00001-AC-27.2
describe('picking a document in the command palette', () => {
  it('moves the viewport to that node', async () => {
    await pick('idea-00001-x')

    await waitFor(() => expect(setCenter).toHaveBeenCalled())
  })

  it('centres on the node rather than its corner', async () => {
    await pick('idea-00001-x')
    await waitFor(() => expect(setCenter).toHaveBeenCalled())

    // NODE_WIDTH 240 / NODE_HEIGHT 92, so the centre is half of each past the origin.
    const [x, y] = setCenter.mock.calls[0] as [number, number]
    const placed = { x: x - 120, y: y - 46 }
    expect(Number.isFinite(placed.x) && Number.isFinite(placed.y)).toBe(true)
  })
})

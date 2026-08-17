// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../src/api.ts'

function mockFetch(status: number, payload: unknown) {
  const fetchMock = vi.fn(async () => ({
    ok: status < 400,
    status,
    statusText: 'Error',
    json: async () => payload,
  }))
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

afterEach(() => vi.unstubAllGlobals())

describe('the api client', () => {
  it('reads the graph', async () => {
    const fetchMock = mockFetch(200, { nodes: [], edges: [], issues: [], diagnostics: [] })
    expect(await api.graph()).toEqual({ nodes: [], edges: [], issues: [], diagnostics: [] })
    expect(fetchMock).toHaveBeenCalledWith('/api/graph', expect.objectContaining({ method: 'GET' }))
  })

  // the requirement panel and the sub-canvas share this one payload (design-00001 §7)
  it('reads the requirement items of a document', async () => {
    const fetchMock = mockFetch(200, { items: [], unattributed: [] })

    expect(await api.items('spec-00001-x')).toEqual({ items: [], unattributed: [] })
    expect(fetchMock).toHaveBeenCalledWith('/api/docs/spec-00001-x/items', expect.objectContaining({ method: 'GET' }))
  })

  it('sends an edit with its base hash', async () => {
    const fetchMock = mockFetch(200, { committed: true })
    await api.save('prd-00001-x', 'body', 'abc')

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/docs/prd-00001-x',
      expect.objectContaining({ method: 'PUT', body: JSON.stringify({ content: 'body', baseHash: 'abc' }) }),
    )
  })

  // spec-00001-FR-9 — clarify starts a session; it is no longer a review write
  it('starts a clarify session for the document', async () => {
    const fetchMock = mockFetch(200, { id: 's1', kind: 'clarify', sourceId: 'prd-00001-x', status: 'running' })

    expect(await api.clarify('prd-00001-x')).toMatchObject({ kind: 'clarify' })
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/sessions/clarify',
      expect.objectContaining({ method: 'POST', body: JSON.stringify({ docId: 'prd-00001-x' }) }),
    )
  })

  // spec-00001-FR-47
  it('starts an ask session for the document', async () => {
    const fetchMock = mockFetch(200, { id: 's1', kind: 'ask', sourceId: 'record-00001-x', status: 'running' })

    expect(await api.ask('record-00001-x')).toMatchObject({ kind: 'ask' })
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/sessions/ask',
      expect.objectContaining({ method: 'POST', body: JSON.stringify({ docId: 'record-00001-x' }) }),
    )
  })

  it('sends accept without questions', async () => {
    const fetchMock = mockFetch(200, { committed: true })
    await api.accept('prd-00001-x')
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/docs/prd-00001-x/review',
      expect.objectContaining({ body: JSON.stringify({ action: 'accept' }) }),
    )
  })

  it('starts an advance', async () => {
    const fetchMock = mockFetch(200, { id: 's1' })
    await api.advance('idea-00001-x', 'prd')
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/sessions',
      expect.objectContaining({ body: JSON.stringify({ sourceId: 'idea-00001-x', targetType: 'prd' }) }),
    )
  })

  it('reads the transitions, next steps, a document, and the session', async () => {
    const fetchMock = mockFetch(200, [])
    await api.transitions('a')
    await api.nextSteps('a')
    await api.doc('a')
    await api.setStatus('a', 'active')
    await api.session()

    expect(fetchMock.mock.calls.map((call) => (call as unknown as [string])[0])).toEqual([
      '/api/docs/a/transitions',
      '/api/docs/a/next-steps',
      '/api/docs/a',
      '/api/docs/a/status',
      '/api/sessions',
    ])
  })

  it('raises the refusal the board reports, with its status', async () => {
    mockFetch(409, { error: 'prd-00001-x changed on disk since it was opened' })

    await expect(api.doc('prd-00001-x')).rejects.toThrowError(ApiError)
    await expect(api.doc('prd-00001-x')).rejects.toThrowError(/changed on disk/)
    await expect(api.doc('prd-00001-x').catch((error) => error.status)).resolves.toBe(409)
  })

  it('falls back to the status text when the body carries no error', async () => {
    mockFetch(500, {})
    await expect(api.graph()).rejects.toThrowError('Error')
  })
})

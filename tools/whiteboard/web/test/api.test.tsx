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
    const fetchMock = mockFetch(200, { nodes: [], edges: [], issues: [], diagnostics: [], idOwners: {} })
    expect(await api.graph()).toEqual({ nodes: [], edges: [], issues: [], diagnostics: [], idOwners: {} })
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

  // spec-00005-FR-1 — a question opens a thread of its own; what comes back is
  // the call's session and the thread it landed on
  it('puts a question to a document', async () => {
    const fetchMock = mockFetch(200, { sessionId: 's1', threadId: 't-1' })

    expect(await api.ask({ docId: 'record-00001-x', question: 'why?' })).toEqual({
      sessionId: 's1',
      threadId: 't-1',
    })
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/sessions/ask',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ docId: 'record-00001-x', question: 'why?' }),
      }),
    )
  })

  // spec-00005-FR-2 and FR-7 — a follow-up names its thread, and a resend says
  // so rather than being guessed at (design-00001 §7)
  it('sends a follow-up and a resend on the thread they belong to', async () => {
    const fetchMock = mockFetch(200, { sessionId: 's2', threadId: 't-1' })

    await api.ask({ docId: 'record-00001-x', question: 'and then?', threadId: 't-1' })
    await api.ask({ docId: 'record-00001-x', question: 'why?', threadId: 't-1', resend: true })

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      '/api/sessions/ask',
      expect.objectContaining({
        body: JSON.stringify({ docId: 'record-00001-x', question: 'and then?', threadId: 't-1' }),
      }),
    )
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/sessions/ask',
      expect.objectContaining({
        body: JSON.stringify({ docId: 'record-00001-x', question: 'why?', threadId: 't-1', resend: true }),
      }),
    )
  })

  // spec-00005-FR-9 — the ask list is its own resource: it outlives the document
  // it is about, so it hangs under no `/api/docs/:id/` (design-00001 §7)
  it('reads the ask list of a document', async () => {
    const threads = [{ id: 't-1', agent: 'claude', exchanges: [] }]
    const fetchMock = mockFetch(200, { threads })

    expect(await api.asks('record-00001-x')).toEqual(threads)
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/asks/record-00001-x',
      expect.objectContaining({ method: 'GET' }),
    )
  })

  // spec-00001-FR-50
  it('starts an audit session for the document', async () => {
    const fetchMock = mockFetch(200, { id: 's1', kind: 'audit', sourceId: 'spec-00001-x', status: 'running' })

    expect(await api.audit('spec-00001-x')).toMatchObject({ kind: 'audit' })
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/sessions/audit',
      expect.objectContaining({ method: 'POST', body: JSON.stringify({ docId: 'spec-00001-x' }) }),
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

  // spec-00001-FR-55 — the agent is named only when there is a choice to name,
  // and an unnamed one leaves no field behind for the server to read
  it('names the agent a session is to run under', async () => {
    const fetchMock = mockFetch(200, { id: 's1' })
    await api.clarify('prd-00001-x', 'codex')
    await api.advance('idea-00001-x', 'prd', 'codex')

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      '/api/sessions/clarify',
      expect.objectContaining({ body: JSON.stringify({ docId: 'prd-00001-x', agent: 'codex' }) }),
    )
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/sessions',
      expect.objectContaining({
        body: JSON.stringify({ sourceId: 'idea-00001-x', targetType: 'prd', agent: 'codex' }),
      }),
    )
  })

  // spec-00001-FR-53 — the prefill asks for a type and writes nothing; the save
  // is what creates, and it goes to the collection, not to an id that has no
  // document behind it yet
  it('takes a prefill for a new document and saves it as a create', async () => {
    const fetchMock = mockFetch(200, { idPrefix: 'idea-00002-', template: '---\nid: idea-00001-example\n---\n' })
    expect(await api.createPrefill('idea')).toMatchObject({ idPrefix: 'idea-00002-' })
    await api.createDoc('idea-00002-notes', 'body')

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      '/api/create?type=idea',
      expect.objectContaining({ method: 'GET' }),
    )
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/docs',
      expect.objectContaining({ method: 'POST', body: JSON.stringify({ id: 'idea-00002-notes', content: 'body' }) }),
    )
  })

  // spec-00001-FR-54 — the list and one entry read whole
  it('reads the session history and one transcript', async () => {
    const fetchMock = mockFetch(200, [])
    await api.sessionHistory()
    await api.sessionTranscript('s1')

    expect(fetchMock.mock.calls.map((call) => (call as unknown as [string])[0])).toEqual([
      '/api/sessions/history',
      '/api/sessions/history/s1',
    ])
  })

  it('reads the transitions, next steps, a document, and the sessions', async () => {
    const fetchMock = mockFetch(200, { sessions: [] })
    await api.transitions('a')
    await api.nextSteps('a')
    await api.doc('a')
    await api.setStatus('a', 'active')
    await api.sessions()

    expect(fetchMock.mock.calls.map((call) => (call as unknown as [string])[0])).toEqual([
      '/api/docs/a/transitions',
      '/api/docs/a/next-steps',
      '/api/docs/a',
      '/api/docs/a/status',
      '/api/sessions',
    ])
  })

  /**
   * spec-00003-AC-9.1 / AC-4.1 — the payload is every session the server holds,
   * running and ended alike, and the board is handed all of them: the panel lists
   * them (spec-00003-FR-4) and choosing between them is its business (FR-5).
   * (Before the sixteenth round this read picked one session off the list; the
   * pick is the board's presentation state now, so it moved to `useBoard`.)
   */
  it('reads every session the server holds', async () => {
    mockFetch(200, {
      sessions: [
        { id: 's1', kind: 'ask', agent: 'claude', sourceId: 'spec-00001-a', status: 'running', startedAt: 'a' },
        { id: 's2', kind: 'ask', agent: 'claude', sourceId: 'spec-00002-b', status: 'exited', startedAt: 'b' },
        { id: 's3', kind: 'clarify', agent: 'claude', sourceId: 'spec-00003-c', status: 'terminated', startedAt: 'c' },
      ],
    })

    expect((await api.sessions()).map((session) => session.id)).toEqual(['s1', 's2', 's3'])
  })

  // spec-00001-FR-49 — the one way out of a session that will not end (issue-00010)
  it('stops the running session', async () => {
    const fetchMock = mockFetch(200, { id: 's1', kind: 'clarify', sourceId: 'prd-00001-x', status: 'exited' })

    expect(await api.stopSession('s1')).toMatchObject({ status: 'exited' })
    expect(fetchMock).toHaveBeenCalledWith('/api/sessions/s1', expect.objectContaining({ method: 'DELETE' }))
  })

  it('raises the refusal the board reports, with its status', async () => {
    mockFetch(409, { error: 'prd-00001-x changed on disk since it was opened' })

    await expect(api.doc('prd-00001-x')).rejects.toThrowError(ApiError)
    await expect(api.doc('prd-00001-x')).rejects.toThrowError(/changed on disk/)
    await expect(api.doc('prd-00001-x').catch((error) => error.status)).resolves.toBe(409)
  })

  // spec-00001-FR-52 — the gate names its gaps in the body, so the refusal the
  // board reports has to carry them through
  it('carries the gaps a resolved-gate refusal names', async () => {
    mockFetch(422, { error: 'plan-00001-x has unverified items', gaps: ['spec-00001-FR-1', 'idea-09999-ghost'] })

    const error = await api.setStatus('plan-00001-x', 'resolved').catch((thrown) => thrown)

    expect(error).toBeInstanceOf(ApiError)
    expect(error.status).toBe(422)
    expect(error.gaps).toEqual(['spec-00001-FR-1', 'idea-09999-ghost'])
  })

  // A refusal that is not the gate's has no gaps field, and must not grow one.
  it('leaves the gaps unset on a refusal that names none', async () => {
    mockFetch(422, { error: 'draft → resolved is not a legal transition' })

    const error = await api.setStatus('plan-00001-x', 'resolved').catch((thrown) => thrown)

    expect(error.gaps).toBeUndefined()
  })

  it('falls back to the status text when the body carries no error', async () => {
    mockFetch(500, {})
    await expect(api.graph()).rejects.toThrowError('Error')
  })
})

/**
 * issue-00016: a node key is not always a document id. An anomalous document —
 * no id in its front matter, or an id it collides on — is keyed by its file
 * path, and a path carries slashes. Every `/api/docs/:id` call has to encode
 * the key, or the slashes are read as further path segments and the request
 * never reaches the document.
 */
describe('addressing a document whose key is a file path', () => {
  const PATH_KEY = 'spec/duplicate-b.md'
  const ENCODED = 'spec%2Fduplicate-b.md'

  it('encodes the key when reading the document', async () => {
    const fetchMock = mockFetch(200, { path: PATH_KEY, content: '', hash: 'h' })
    await api.doc(PATH_KEY)
    expect(fetchMock).toHaveBeenCalledWith(`/api/docs/${ENCODED}`, expect.anything())
  })

  it('encodes the key when saving the document — the repair path of spec-00002-FR-9', async () => {
    const fetchMock = mockFetch(200, { committed: true })
    await api.save(PATH_KEY, 'fixed', 'h')
    expect(fetchMock).toHaveBeenCalledWith(`/api/docs/${ENCODED}`, expect.objectContaining({ method: 'PUT' }))
  })

  it('encodes the key on every other call that addresses a document', async () => {
    const fetchMock = mockFetch(200, [])
    await api.items(PATH_KEY)
    await api.transitions(PATH_KEY)
    await api.nextSteps(PATH_KEY)
    await api.setStatus(PATH_KEY, 'active')
    await api.accept(PATH_KEY)

    expect(fetchMock.mock.calls.map(([url]: unknown[]) => url)).toEqual([
      `/api/docs/${ENCODED}/items`,
      `/api/docs/${ENCODED}/transitions`,
      `/api/docs/${ENCODED}/next-steps`,
      `/api/docs/${ENCODED}/status`,
      `/api/docs/${ENCODED}/review`,
    ])
  })
})

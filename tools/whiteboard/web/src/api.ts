import type { FlowConfig, FlowStep } from '../../src/config.ts'
import type { DocContent, DocGraph } from '../../src/docRepository.ts'
import type { ActionResult, CoverageRow } from '../../src/docService.ts'
import type { ItemsView } from '../../src/requirements.ts'
import type { SessionHistoryEntry, SessionHistoryMeta } from '../../src/sessionHistory.ts'
import type { SessionInfo, SessionListing } from '../../src/sessionManager.ts'

export type {
  CoverageRow,
  DocContent,
  DocGraph,
  FlowConfig,
  FlowStep,
  ItemsView,
  SessionHistoryEntry,
  SessionHistoryMeta,
  SessionInfo,
  SessionListing,
}

/**
 * What `GET /api/config` hands the board: the effective flow config, plus the
 * two type sets the code — not the config file — owns (spec-00001-FR-56). The
 * board keeps no copy of either: what it shows follows this payload, so the
 * front end and the server cannot drift apart (spec-00001-AC-56.2).
 */
export type ConfigPayload = FlowConfig & {
  clarifiable: string[]
  auditable: string[]
}

/**
 * The prefill of a new document: the id prefix `rule-00001-BR-18` allocated and
 * the type's template, neither of which is on disk yet — creating happens on
 * save (spec-00001-FR-53).
 */
export interface CreatePrefill {
  idPrefix: string
  template: string
}


/** A refused action; `status` is what the board shows the user (409 conflict, 422 rejected). */
export class ApiError extends Error {
  readonly status: number
  /**
   * The gaps a `resolved` gate refusal names one by one — item ids, or ids it
   * could not resolve (spec-00001-FR-52). Only that refusal carries them, so
   * their presence is what tells the gate's 422 from any other.
   */
  readonly gaps?: string[]

  constructor(status: number, message: string, gaps?: string[]) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.gaps = gaps
  }
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const response = await fetch(path, {
    method,
    headers: body === undefined ? undefined : { 'content-type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const payload = await response.json()
  if (!response.ok) {
    throw new ApiError(response.status, payload.error ?? response.statusText, payload.gaps)
  }
  return payload as T
}

/**
 * A document's own path segment. What addresses a document is its **node key**,
 * which is its id only while the front matter carries one: an anomalous
 * document is keyed by its file path (spec-00001-FR-2), and so is each side of
 * an id collision (spec-00002-FR-8). A path carries slashes, and a slash left
 * raw in the URL becomes another path segment, so the request never reaches the
 * document — the defect of issue-00016. Express 5 hands `%2F` back to `:id` as
 * a slash, so the encoding is the whole fix and the routes are untouched.
 */
function at(key: string): string {
  return `/api/docs/${encodeURIComponent(key)}`
}

export const api = {
  graph: () => request<DocGraph>('GET', '/api/graph'),
  // The global coverage view's one read (spec-00002-FR-10). Asked for only while
  // the view is open: it is the heaviest read the board has (design-00001 §6).
  coverage: () => request<CoverageRow[]>('GET', '/api/coverage'),
  config: () => request<ConfigPayload>('GET', '/api/config'),
  doc: (id: string) => request<DocContent>('GET', at(id)),
  items: (id: string) => request<ItemsView>('GET', `${at(id)}/items`),
  save: (id: string, content: string, baseHash: string) =>
    request<ActionResult>('PUT', at(id), { content, baseHash }),
  transitions: (id: string) => request<string[]>('GET', `${at(id)}/transitions`),
  setStatus: (id: string, to: string) => request<ActionResult>('POST', `${at(id)}/status`, { to }),
  accept: (id: string) => request<ActionResult>('POST', `${at(id)}/review`, { action: 'accept' }),
  nextSteps: (id: string) => request<FlowStep[]>('GET', `${at(id)}/next-steps`),
  /**
   * The session the terminal is to show, off the list of every session the
   * server holds (`GET /api/sessions`, design-00001 §7): the newest running one,
   * or — when nothing runs — the newest there was, so the panel still says how
   * the last one ended. Several running sessions are all in the payload;
   * choosing between them is the session panel's business (spec-00003-FR-4,
   * FR-5), and this is the one a reconnecting board reattaches to
   * (spec-00003-FR-9).
   */
  session: async (): Promise<{ current: SessionInfo | null }> => {
    const { sessions } = await request<{ sessions: SessionListing[] }>('GET', '/api/sessions')
    const running = sessions.filter((session) => session.status === 'running')
    return { current: (running.length > 0 ? running : sessions).at(-1) ?? null }
  },
  // Every session entry may name which agent runs it; leaving it out is what a
  // single-agent config does, and the server then takes the first
  // (spec-00001-FR-55). `undefined` drops out of the body on its own, so an
  // unspecified agent is an absent field, not a null one.
  advance: (sourceId: string, targetType: string, agent?: string) =>
    request<SessionInfo>('POST', '/api/sessions', { sourceId, targetType, agent }),
  // Clarify, ask and audit are sessions, not writes: the agent does the
  // questioning, the answering and the auditing in the terminal
  // (spec-00001-FR-9, FR-47, FR-50).
  clarify: (docId: string, agent?: string) => request<SessionInfo>('POST', '/api/sessions/clarify', { docId, agent }),
  ask: (docId: string, agent?: string) => request<SessionInfo>('POST', '/api/sessions/ask', { docId, agent }),
  audit: (docId: string, agent?: string) => request<SessionInfo>('POST', '/api/sessions/audit', { docId, agent }),
  // The way out of a session that will not end by itself; what comes back is the
  // session as it finished (spec-00001-FR-49). The session is named: the stop
  // acts on the one the terminal is showing (spec-00003-FR-5).
  stopSession: (id: string) => request<SessionInfo>('DELETE', `/api/sessions/${encodeURIComponent(id)}`),
  // Creating is two steps, and only the second one writes: the prefill takes a
  // number and a template, the save creates the file (spec-00001-FR-53). The
  // path is its own rather than under `/api/docs/:id` — there is no id yet.
  createPrefill: (type: string) =>
    request<CreatePrefill>('GET', `/api/create?type=${encodeURIComponent(type)}`),
  createDoc: (id: string, content: string) => request<ActionResult>('POST', '/api/docs', { id, content }),
  // The sessions that have already ended, and any one of them read whole
  // (spec-00001-FR-54).
  sessionHistory: () => request<SessionHistoryMeta[]>('GET', '/api/sessions/history'),
  sessionTranscript: (id: string) => request<SessionHistoryEntry>('GET', `/api/sessions/history/${id}`),
}

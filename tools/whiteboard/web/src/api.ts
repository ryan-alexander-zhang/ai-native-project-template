import type { SelectionAnchor } from '../../src/annotationAnchor.ts'
import type { Annotation, AnnotationType } from '../../src/annotationStore.ts'
import type { AnnotationListView, SubmitPreview, SubmitResult } from '../../src/annotations.ts'
import type { AskExchange, AskThread } from '../../src/askStore.ts'
import type { FlowConfig, FlowStep } from '../../src/config.ts'
import type { CowriteMaterials } from '../../src/cowrite.ts'
import type { DocContent, DocGraph } from '../../src/docRepository.ts'
import type { ActionResult, CoverageRow } from '../../src/docService.ts'
import type { ItemsView } from '../../src/requirements.ts'
import type { SessionHistoryEntry, SessionHistoryMeta } from '../../src/sessionHistory.ts'
import type { SessionInfo, SessionListing } from '../../src/sessionManager.ts'

export type {
  Annotation,
  AnnotationListView,
  AnnotationType,
  AskExchange,
  AskThread,
  SubmitPreview,
  SubmitResult,
  CoverageRow,
  CowriteMaterials,
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
 * What one question carries (design-00001 §7). No `threadId` opens a thread of
 * its own — a headless first call; one that names a thread is that thread's
 * follow-up, and `resend` says its last question is being put again rather than
 * a new one asked (spec-00005-FR-2, FR-7).
 */
export interface AskSubmit {
  docId: string
  question: string
  agent?: string
  threadId?: string
  resend?: boolean
}

/**
 * What one cowrite launch carries (design-00001 §11.2): the document it writes,
 * **or** the type and slug of one to be filed first — the two forms are
 * exclusive. The materials are optional in both: empty materials are a launch
 * like any other, unlike an empty question (spec-00006-AC-3.3).
 */
export interface CowriteSubmit {
  docId?: string
  create?: { type: string; slug: string }
  agent?: string
  materials?: CowriteMaterials
}

/**
 * One entry of the effective agent list as a payload carries it (design-00001
 * §7). `headless` is a **boolean** from the twenty-sixth round on — whether the
 * merged entry declares the form, which is the only part of it a caller needs —
 * and `source` says which layer the entry came from (spec-00009-FR-7). Disabled
 * entries are not in the list at all (spec-00009-AC-3.8).
 *
 * It is the front end's own type rather than the server's `AgentConfig`: the
 * payload is narrower than an entry now, and reusing the entry would let a field
 * that is not on the wire be read as if it were (design-00002 §18.4).
 */
export interface EffectiveAgent {
  name: string
  headless: boolean
  source: AgentSource
  default?: boolean
}

/** Which layer an effective entry came from (spec-00009-FR-3). */
export type AgentSource = 'project' | 'local' | 'overridden'

/** An agent's headless declaration as the settings panel edits it, structure for structure (design-00002 §18.3). */
export interface HeadlessDecl {
  first: string[]
  resume: string[]
  /** One of the captures the code holds; the panel offers the set `captures` names. */
  capture: string
}

/** One whole agent entry — the project layer's form (design-00001 §3). */
export interface AgentEntry {
  name: string
  command: string
  args: string[]
  cwd?: string
  model?: string
  env?: Record<string, string>
  headless?: HeadlessDecl
}

/**
 * What the local layer may say about one project entry: any key but `cwd`, which
 * is the write-scope barrier and never local (design-00001 §13.1). Each key it
 * carries replaces the project's whole key — undoing one is deleting it. The one
 * null the file admits is `headless: null`, which takes the project entry's
 * declaration away rather than replacing it.
 */
export type AgentOverride = Partial<Omit<AgentEntry, 'name' | 'cwd' | 'headless'>> & {
  headless?: HeadlessDecl | null
}

/**
 * An entry only this machine declares: an override that must carry a `command`,
 * and that has no project declaration to take away, so no null of its own.
 */
export type LocalAgentEntry = Omit<AgentOverride, 'headless'> & { command: string; headless?: HeadlessDecl }

/** The local layer file, whole (design-00001 §13.1). A save PUTs one of these. */
export interface LocalAgentSettings {
  default?: string
  disabled?: string[]
  overrides?: Record<string, AgentOverride>
  entries?: Record<string, LocalAgentEntry>
}

/** One thing the local layer says that points at nothing; the layer still holds (spec-00009-FR-4). */
export interface AgentNotice {
  name: string
  message: string
}

/** Why the local layer is being ignored whole, and at which key (spec-00009-FR-4). */
export interface AgentSettingsError {
  message: string
  at?: string
}

/** What the settings panel reads on every open (design-00001 §7, design-00002 §18.1). */
export interface AgentSettingsView {
  project: AgentEntry[]
  /** The local file as it stands, or nothing when there is none the board could read. */
  local: LocalAgentSettings | null
  effective: EffectiveAgent[]
  /** The capture names the code holds, for the headless dropdown. */
  captures: string[]
  error?: AgentSettingsError
  notices: AgentNotice[]
}

/** What a save answers with: the list it just made effective, and what pointed at nothing. */
export interface AgentSettingsSaved {
  effective: EffectiveAgent[]
  notices: AgentNotice[]
}

/**
 * What `GET /api/config` hands the board: the effective flow config, plus the
 * two type sets the code — not the config file — owns (spec-00001-FR-56). The
 * board keeps no copy of either: what it shows follows this payload, so the
 * front end and the server cannot drift apart (spec-00001-AC-56.2). `agents` is
 * the effective agent list rather than the config's own `agents`, which is only
 * the project layer of it (spec-00009-FR-3).
 */
export type ConfigPayload = Omit<FlowConfig, 'agents'> & {
  agents: EffectiveAgent[]
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


/** What one new annotation carries (design-00001 §12.1); the quote is the server's to derive. */
export interface AnnotationInput {
  type: AnnotationType
  text: string
  anchor: SelectionAnchor
}

/** What a change to an unsubmitted annotation may carry: its text, its type, a new selection. */
export interface AnnotationChange {
  text?: string
  type?: AnnotationType
  anchor?: SelectionAnchor
}

/**
 * What one unified submit carries (design-00001 §12.3). `unsavedChanges` is the
 * **front end's declaration** — an unsaved buffer lives in the browser and the
 * server has no second place to observe it — so the entry here is the one and
 * only judgment of it (design-00002 §16.5). The agent of each path is named on
 * its own, since the two choose from different sets (spec-00007-FR-5).
 */
export interface AnnotationSubmit {
  unsavedChanges: boolean
  agents?: { question?: string; cowrite?: string }
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
  /**
   * The word the server put on a refusal that has one (design-00001 §7):
   * `doc-busy`, `cap-reached`, `doc-missing`. Two refusals answer 409 for quite
   * different reasons — a file that moved under the buffer and a document a
   * cowrite session holds — and only this tells them apart, so the way out the
   * board offers is the right one (spec-00006-AC-10.3).
   */
  readonly reason?: string
  /**
   * The key a refusal is at, as the dotted path the local layer is addressed by
   * — `overrides.claude.model` (design-00001 §13.1). It is what puts a save's
   * 422 under the field it is about rather than at the top of the panel
   * (design-00002 §18.3).
   */
  readonly at?: string

  constructor(status: number, message: string, gaps?: string[], reason?: string, at?: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.gaps = gaps
    this.reason = reason
    this.at = at
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
    throw new ApiError(response.status, payload.error ?? response.statusText, payload.gaps, payload.reason, payload.at)
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
   * Every session the server holds — running and ended alike, oldest first
   * (`GET /api/sessions`, design-00001 §7). The whole list, not a pick off it:
   * the session panel lists them all (spec-00003-FR-4), the top bar counts them
   * (FR-6), the node markers read the running ones (FR-10), and a board opening
   * fresh reattaches to one of them (FR-9). Which one the terminal shows is the
   * board's own presentation state, never the payload's (FR-5).
   */
  sessions: async (): Promise<SessionListing[]> => {
    const { sessions } = await request<{ sessions: SessionListing[] }>('GET', '/api/sessions')
    return sessions
  },
  // Every session entry may name which agent runs it; leaving it out is what a
  // single-agent config does, and the server then takes the first
  // (spec-00001-FR-55). `undefined` drops out of the body on its own, so an
  // unspecified agent is an absent field, not a null one.
  advance: (sourceId: string, targetType: string, agent?: string) =>
    request<SessionInfo>('POST', '/api/sessions', { sourceId, targetType, agent }),
  // Clarify and audit are sessions, not writes: the agent does the questioning
  // and the auditing in the terminal (spec-00001-FR-9, FR-50).
  clarify: (docId: string, agent?: string) => request<SessionInfo>('POST', '/api/sessions/clarify', { docId, agent }),
  audit: (docId: string, agent?: string) => request<SessionInfo>('POST', '/api/sessions/audit', { docId, agent }),
  /**
   * One question on one document (spec-00005-FR-1). It is a session too, but a
   * headless one: what comes back is the call's registry session and the thread
   * the question landed on, and there is no terminal to open (FR-3).
   */
  ask: (submit: AskSubmit) =>
    request<{ sessionId: string; threadId: string }>('POST', '/api/sessions/ask', submit),
  /**
   * One cowrite session (spec-00006-FR-1, FR-2). What comes back is the session
   * and the document it is on — which the create form only learns here, since the
   * number is the server's — and, for that form alone, the `error` of a filing
   * whose commit failed: the file is on disk and the session goes ahead, so it is
   * a notice rather than a refusal (spec-00001-FR-20, design-00001 §11.2).
   */
  cowrite: (submit: CowriteSubmit) =>
    request<{ sessionId: string; docId: string; error?: string }>('POST', '/api/sessions/cowrite', submit),
  /**
   * A document's ask list (spec-00005-FR-9). Asked for only while the list is
   * on show — it is the fourth item of the one refresh path, and a board that is
   * not showing a list has nothing to do with the answer (design-00002 §10).
   * A document with no list yet answers with no threads, never an error.
   */
  asks: async (docId: string): Promise<AskThread[]> => {
    const { threads } = await request<{ threads: AskThread[] }>(
      'GET',
      `/api/asks/${encodeURIComponent(docId)}`,
    )
    return threads
  },
  /**
   * A document's annotations, each with where its anchor lands on the disk just
   * now, the batches of its submitted issues, and the submit statement
   * (design-00001 §12.3). Read while that document's **editor** is open, not
   * merely its list: the traces have to be right in the two other view states
   * too (design-00002 §16.8).
   */
  annotations: (docId: string) =>
    request<AnnotationListView>('GET', `/api/annotations/${encodeURIComponent(docId)}`),
  addAnnotation: (docId: string, input: AnnotationInput) =>
    request<{ annotation: Annotation }>('POST', `/api/annotations/${encodeURIComponent(docId)}`, input),
  changeAnnotation: (docId: string, annotationId: string, change: AnnotationChange) =>
    request<{ annotation: Annotation }>(
      'PATCH',
      `/api/annotations/${encodeURIComponent(docId)}/${encodeURIComponent(annotationId)}`,
      change,
    ),
  removeAnnotation: (docId: string, annotationId: string) =>
    request<{ annotationId: string }>(
      'DELETE',
      `/api/annotations/${encodeURIComponent(docId)}/${encodeURIComponent(annotationId)}`,
    ),
  /**
   * One unified submit of a document's unsubmitted annotations
   * (spec-00007-FR-5). 4xx means the batch did not happen at all; 200 means it
   * ran and every per-annotation outcome is in the payload (design-00001 §12.3).
   */
  submitAnnotations: (docId: string, submit: AnnotationSubmit) =>
    request<SubmitResult>('POST', `/api/annotations/${encodeURIComponent(docId)}/submit`, submit),
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
  /**
   * Both agent layers as they stand (spec-00009-FR-7). Read on every open of the
   * settings panel rather than kept: a local file edited by hand shows its error
   * the next time the panel is opened (design-00002 §18.1).
   */
  agentSettings: () => request<AgentSettingsView>('GET', '/api/settings/agents'),
  /**
   * The local layer, saved whole (spec-00009-FR-5). What comes back is the list
   * the save just made effective, which is what the page it was saved from shows
   * from then on — no re-read of the config, and no other page told
   * (spec-00009-FR-8, design-00001 §13.3).
   */
  saveAgentSettings: (local: LocalAgentSettings) =>
    request<AgentSettingsSaved>('PUT', '/api/settings/agents', local),
  // The sessions that have already ended, and any one of them read whole
  // (spec-00001-FR-54).
  sessionHistory: () => request<SessionHistoryMeta[]>('GET', '/api/sessions/history'),
  sessionTranscript: (id: string) => request<SessionHistoryEntry>('GET', `/api/sessions/history/${id}`),
}

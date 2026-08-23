import type { Server } from 'node:http'
import express, { type Express, type NextFunction, type Request, type Response } from 'express'
import { WebSocketServer } from 'ws'
import { type Expectation, findProduct, markProduct, productProblems, taskInstruction } from './advance.ts'
import { auditableTypes } from './auditRules.ts'
import { clarifiableTypes } from './clarifyRules.ts'
import type { FlowConfig } from './config.ts'
import { ConflictError, DocService, GateError } from './docService.ts'
import type { DirtySnapshot } from './gitLayer.ts'
import { listSessionHistory, readSessionHistory } from './sessionHistory.ts'
import {
  NoSessionError,
  SessionBusyError,
  SessionManager,
  type SessionOutcome,
  type SessionPlan,
  type SpawnPty,
} from './sessionManager.ts'
import { spawnPty } from './pty.ts'
import { DocsWatcher } from './watcher.ts'
import { WorkflowError, allocateNumber, idPrefix } from './workflow.ts'

export interface BoardOptions {
  repoRoot: string
  docsDir: string
  config: FlowConfig
  spawn?: SpawnPty
}

/** Wires the modules into one board: doc service, session manager, and the HTTP/WS surface. */
export class Board {
  readonly app: Express
  readonly docs: DocService
  readonly sessions: SessionManager
  /** Live only while the board is listening: a board nobody can connect to has nobody to tell. */
  readonly watcher: DocsWatcher
  private readonly repoRoot: string
  /**
   * What the last advance was asked for, re-checked against the disk on every
   * graph build (spec-00001-FR-17 as amended, issue-00014). The mark is a
   * reading, not a state: keeping the findings instead would go on marking a
   * document the user has already fixed.
   */
  private lastExpectation?: Expectation

  constructor(options: BoardOptions) {
    const { repoRoot, docsDir, config, spawn = spawnPty } = options
    this.repoRoot = repoRoot
    this.docs = new DocService(repoRoot, docsDir, config)
    // Everything written from outside the board arrives as this signal, so it is
    // also where the parsed tree goes stale (spec-00001 §7 非功能项).
    this.watcher = new DocsWatcher(docsDir, () => this.docs.invalidate())
    this.sessions = new SessionManager({
      agents: config.agents,
      maxSessions: config.maxSessions,
      repoRoot,
      spawn,
      snapshot: () => this.docs.snapshotDocs(),
      onExit: (plan, baseline) => this.finishSession(plan, baseline),
    })
    this.app = this.buildApp(config)
  }

  /**
   * The graph as the board renders it: the disk, plus the last advance's product
   * check (spec-00001-FR-17). The check is re-run here rather than remembered, so
   * a product fixed on disk stops being marked at the very next refresh, with no
   * further advance (spec-00001-AC-17.3, issue-00014); once it validates clean
   * there is nothing left to re-check.
   */
  graph() {
    const graph = this.docs.graph()
    if (!this.lastExpectation) return graph
    const product = findProduct(graph, this.lastExpectation.idPrefix)
    if (!product) return graph
    const problems = productProblems(product, this.lastExpectation)
    if (problems.length === 0) {
      this.lastExpectation = undefined
      return graph
    }
    return markProduct(graph, product.id, problems)
  }

  /**
   * The end of a session, whatever it left behind. The wrap-up is told to every
   * connected board unconditionally: a session that wrote nothing has no commit
   * and no file event to be noticed by, and a board that hears nothing goes on
   * showing a session the server has already finished with (spec-00001-AC-12.8,
   * issue-00013).
   */
  private async finishSession(plan: SessionPlan, baseline: DirtySnapshot): Promise<SessionOutcome> {
    // Whatever the session wrote, the tree the board parsed is out of date
    // (spec-00001 §7 非功能项) — and the wrap-up itself reads the graph.
    this.docs.invalidate()
    try {
      return await this.wrapUpSession(plan, baseline)
    } finally {
      this.watcher.signal()
    }
  }

  /**
   * Commit what the session wrote, then check it against what was asked for
   * (spec-00001-FR-17). `before` is that session's **own** baseline, handed over
   * by the registry: with several sessions running, the dirt one of them
   * inherited says nothing about what another may commit (spec-00003-FR-8).
   */
  private async wrapUpSession(plan: SessionPlan, before: DirtySnapshot): Promise<SessionOutcome> {
    // Clarify, ask and audit were asked for no new document, so there is nothing
    // to check: their commit is named by the kind and carries the document they
    // were about (spec-00001-AC-14.7, AC-14.8, AC-50.3).
    if (plan.expectation === undefined) {
      const outcome = await this.docs.commitSessionChanges(plan.sourceId, before, plan.kind)
      return { docId: plan.sourceId, problems: [], committed: outcome.committed, error: outcome.error }
    }
    return this.finishAdvance(plan.expectation, before)
  }

  private async finishAdvance(expectation: Expectation, before: DirtySnapshot): Promise<SessionOutcome> {
    const product = findProduct(this.docs.graph(), expectation.idPrefix)
    if (!product) {
      this.lastExpectation = undefined
      const outcome = await this.docs.commitSessionChanges(expectation.sourceId, before)
      return { problems: [], committed: outcome.committed, error: outcome.error }
    }
    const problems = productProblems(product, expectation)
    // The expectation is what is kept, never the findings: the graph re-checks it
    // against the disk every time (issue-00014).
    this.lastExpectation = problems.length > 0 ? expectation : undefined
    const outcome = await this.docs.commitSessionChanges(product.id, before)
    return { docId: product.id, problems, committed: outcome.committed, error: outcome.error }
  }

  private buildApp(config: FlowConfig): Express {
    const app = express()
    app.use(express.json({ limit: '4mb' }))
    app.use(express.static(new URL('../dist/web', import.meta.url).pathname))

    app.get('/api/graph', (_req, res) => res.json(this.graph()))
    // The global coverage view (spec-00002-FR-10): every spec and rule in one
    // read. The heaviest read the board has, which is why it is asked for only
    // while the view is open (design-00001 §6) and why the bodies under it are
    // cached (design-00001 §2).
    app.get('/api/coverage', (_req, res) => res.json(this.docs.coverage()))
    // The effective config, plus the two type sets the code holds
    // (spec-00001-FR-56): the front end reads its entry rulings off this one
    // payload instead of keeping a copy of rule-00001-BR-20 and BR-23.
    app.get('/api/config', (_req, res) =>
      res.json({ ...config, clarifiable: clarifiableTypes(), auditable: auditableTypes() }),
    )

    // Creating a document (spec-00001-FR-53): the prefill first — a number and a
    // template, nothing written — then the save, which is the write path's create
    // branch. Its own path, so no id can be read as the word «new» (design-00001 §7).
    app.get('/api/create', (req, res) => res.json(this.docs.newDocument(typeParam(req.query.type))))
    app.post('/api/docs', async (req, res) => {
      const { id, content } = req.body ?? {}
      if (typeof id !== 'string' || typeof content !== 'string') {
        throw new WorkflowError('a create needs an id and the content to save')
      }
      res.status(201).json(await this.docs.create(id, content))
    })

    app.get('/api/docs/:id', (req, res) => res.json(this.docs.read(req.params.id)))
    app.get('/api/docs/:id/items', (req, res) => res.json(this.docs.items(req.params.id)))
    app.get('/api/docs/:id/transitions', (req, res) => res.json(this.docs.transitions(req.params.id)))
    app.get('/api/docs/:id/next-steps', (req, res) => res.json(this.docs.nextSteps(req.params.id)))

    app.put('/api/docs/:id', async (req, res) => {
      res.json(await this.docs.save(req.params.id, req.body.content, req.body.baseHash))
    })
    app.post('/api/docs/:id/status', async (req, res) => {
      res.json(await this.docs.changeStatus(req.params.id, req.body.to))
    })
    app.post('/api/docs/:id/review', async (req, res) => {
      res.json(await this.docs.review(req.params.id, req.body))
    })

    // Every session since the server came up (design-00001 §7): the session panel
    // reads it, and so does a board reconnecting to the sessions it left running
    // (spec-00003-FR-4, FR-9).
    app.get('/api/sessions', (_req, res) => res.json({ sessions: this.sessions.list() }))
    // Sessions that have ended, read straight from `.whiteboard/sessions/`
    // (spec-00001-FR-54) — which is what makes the history outlive a restart.
    app.get('/api/sessions/history', (_req, res) => res.json(listSessionHistory(this.repoRoot)))
    app.get('/api/sessions/history/:id', (req, res) => {
      const entry = readSessionHistory(this.repoRoot, req.params.id)
      if (!entry) throw new NoSessionError(`there is no session history for ${req.params.id}`)
      res.json(entry)
    })
    app.post('/api/sessions', (req, res) => res.json(this.startSession(req.body)))
    // The other three session kinds: same channel, same concurrency rules
    // (spec-00003-FR-2, FR-3), each with its own ruling (FR-9, FR-47, FR-51)
    // made in the doc service.
    app.post('/api/sessions/clarify', (req, res) => {
      res.json(this.sessions.start(this.docs.clarifyPlan(docIdOf(req.body)), agentOf(req.body)))
    })
    app.post('/api/sessions/ask', (req, res) => {
      res.json(this.sessions.start(this.docs.askPlan(docIdOf(req.body)), agentOf(req.body)))
    })
    app.post('/api/sessions/audit', (req, res) => {
      res.json(this.sessions.start(this.docs.auditPlan(docIdOf(req.body)), agentOf(req.body)))
    })
    // The way out of a session that will not end by itself (spec-00001-FR-49);
    // the wrap-up it answers with has already run. The session is named, because
    // the stop acts on the one the terminal is showing and the refusal is judged
    // per session (spec-00003-FR-5).
    app.delete('/api/sessions/:id', async (req, res) => {
      res.json(await this.sessions.terminate(req.params.id))
    })

    app.use(errorHandler)
    return app
  }

  /** spec-00001-FR-10 and FR-11: only a step the flow config declares may be started. */
  private startSession(body: { sourceId?: string; targetType?: string; agent?: unknown }) {
    const { sourceId, targetType } = body
    if (typeof sourceId !== 'string' || typeof targetType !== 'string') {
      throw new WorkflowError('an advance needs a sourceId and a targetType')
    }
    const step = this.docs.nextSteps(sourceId).find((candidate) => candidate.next === targetType)
    if (!step) {
      throw new WorkflowError(`${targetType} is not a next step of ${sourceId}`)
    }
    // The numbers of advances still running count as taken (spec-00003-FR-1):
    // their documents are not on disk yet, so the graph alone would hand the same
    // number to two parallel advances (spec-00003-AC-1.3). The number is taken
    // before the admission below and only becomes a reservation if that start is
    // admitted — a refused start reserves nothing.
    const number = allocateNumber(this.docs.graph(), targetType, this.sessions.reservedNumbers(targetType))
    const expectation: Expectation = {
      targetType,
      number,
      idPrefix: idPrefix(targetType, number),
      carry: step.carry,
      sourceId,
    }
    return this.sessions.start(
      {
        kind: 'advance',
        sourceId,
        instruction: taskInstruction(expectation, this.docs.pathOf(sourceId)),
        expectation,
      },
      agentOf(body),
    )
  }

  /**
   * Serve, and open the two sockets: the session terminal, and the docs-change
   * signal every board follows (spec-00001-FR-42). Each socket server takes the
   * upgrade handed to it by the one router below — two of them bound to the same
   * http server would each abort the other's handshakes.
   */
  listen(port: number): Server {
    const server = this.app.listen(port)
    const terminals = new WebSocketServer({ noServer: true })
    const events = new WebSocketServer({ noServer: true })
    const routes: Record<string, WebSocketServer> = { '/api/terminal': terminals, '/api/events': events }

    server.on('upgrade', (request, socket, head) => {
      const route = routes[new URL(request.url ?? '/', 'http://board').pathname]
      if (!route) {
        socket.destroy()
        return
      }
      route.handleUpgrade(request, socket, head, (connection) => route.emit('connection', connection, request))
    })

    // Which session the terminal is showing rides in the query (design-00001 §7):
    // one channel per session, so output and keystrokes reach that session and no
    // other (spec-00003-FR-1, FR-5). A connection naming a session the registry
    // does not know — none at all, or one from before a restart — is closed, which
    // is what the front end reads as «nothing to show here».
    terminals.on('connection', (socket, request) => {
      const sessionId = new URL(request.url ?? '/', 'http://board').searchParams.get('sessionId') ?? ''
      let attached: { buffer: string; detach: () => void }
      try {
        attached = this.sessions.attach(sessionId, (data) => socket.send(data))
      } catch {
        socket.close()
        return
      }
      socket.send(attached.buffer)
      // The two kinds of message the terminal sends are told apart by frame
      // type, never by their bytes: a text frame is stdin as it was typed, and a
      // binary frame is the terminal's size (design-00001 §7, issue-00009).
      socket.on('message', (data, isBinary) => {
        if (!isBinary) {
          this.sessions.write(sessionId, data.toString())
          return
        }
        const size = parseSize(data.toString())
        if (size) this.sessions.resize(sessionId, size.cols, size.rows)
      })
      socket.on('close', () => attached.detach())
    })

    // The frame is the whole message: a board that gets one re-reads the graph
    // and the items it is showing (design-00001 §6).
    events.on('connection', (socket) => {
      const unsubscribe = this.watcher.subscribe(() => {
        if (socket.readyState === socket.OPEN) socket.send('')
      })
      socket.on('close', unsubscribe)
    })

    this.watcher.start()
    server.on('close', () => void this.watcher.close())
    return server
  }
}

/** A clarify, ask or audit request names the one document it is about. */
function docIdOf(body: { docId?: string }): string {
  if (typeof body.docId !== 'string') {
    throw new WorkflowError('a clarify, ask or audit session needs a docId')
  }
  return body.docId
}

/**
 * The agent a session request names, if it names one (spec-00001-FR-55). Absent
 * means the first configured agent, which is every board's behaviour so far;
 * anything that is not a name is refused rather than quietly read as absent.
 */
function agentOf(body: { agent?: unknown } | undefined): string | undefined {
  const agent = body?.agent
  if (agent === undefined || agent === null) return undefined
  if (typeof agent !== 'string') {
    throw new WorkflowError('agent must name one of the agents in the flow config')
  }
  return agent
}

/** The type a create request asks about; anything but one name is no type at all. */
function typeParam(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

const isTerminalSize = (value: unknown): value is number =>
  typeof value === 'number' && Number.isInteger(value) && value > 0

/**
 * A terminal size control frame: the columns and rows the embedded terminal is
 * drawing into. A frame that carries no readable pair is dropped — it is not
 * stdin either, and the session keeps the size it had.
 */
function parseSize(frame: string): { cols: number; rows: number } | undefined {
  try {
    const { cols, rows } = JSON.parse(frame) as { cols?: unknown; rows?: unknown }
    return isTerminalSize(cols) && isTerminalSize(rows) ? { cols, rows } : undefined
  } catch {
    return undefined
  }
}

const STATUS_BY_ERROR: Array<[new (...args: never[]) => Error, number]> = [
  [ConflictError, 409],
  [SessionBusyError, 409],
  [NoSessionError, 404],
  [WorkflowError, 422],
]

/**
 * The word a refusal carries when there is one to carry: the session entries'
 * 409 says whether the target document is busy, the cap is reached, or the
 * document is gone (design-00001 §7). Read off the error rather than decided per
 * route, so the four entries cannot answer the same refusal differently.
 */
function reasonOf(error: Error): { reason?: string } {
  const { reason } = error as { reason?: unknown }
  return typeof reason === 'string' ? { reason } : {}
}

function errorHandler(error: Error, _req: Request, res: Response, _next: NextFunction): void {
  const match = STATUS_BY_ERROR.find(([type]) => error instanceof type)
  // The resolved gate names its gaps in the body (design-00001 §7); every other
  // refusal carries its message alone, so the field's presence is the gate's.
  const gaps = error instanceof GateError ? { gaps: error.gaps } : {}
  res.status(match?.[1] ?? 500).json({ error: error.message, ...gaps, ...reasonOf(error) })
}

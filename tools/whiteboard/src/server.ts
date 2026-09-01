import type { Server } from 'node:http'
import express, { type Express, type NextFunction, type Request, type Response } from 'express'
import { WebSocketServer } from 'ws'
import { type Expectation, findProduct, markProduct, productProblems, taskInstruction } from './advance.ts'
import { auditableTypes } from './auditRules.ts'
import type { SelectionAnchor } from './annotationAnchor.ts'
import { AnnotationConflictError, NoAnnotationError } from './annotationStore.ts'
import { Annotations } from './annotations.ts'
import { AskBusyError, AskStore } from './askStore.ts'
import { clarifiableTypes } from './clarifyRules.ts'
import type { FlowConfig } from './config.ts'
import { type CowriteMaterials, REFERENCE_TYPE } from './cowrite.ts'
import { type ActionResult, ConflictError, DocService, GateError } from './docService.ts'
import type { DirtySnapshot } from './gitLayer.ts'
import type { SpawnHeadless } from './headless.ts'
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
  /** The second seam an ask call runs on (design-00001 §10.1), settable for the same reason `spawn` is. */
  spawnHeadless?: SpawnHeadless
  /**
   * The silence a running session is read as «waiting on the user» after
   * (spec-00003-FR-6). An implementation constant, not a config key
   * (design-00001 §5); it is settable here for the same reason `spawn` is — so a
   * test need not wait out the real ten seconds.
   */
  awaitThresholdMs?: number
}

/** Wires the modules into one board: doc service, session manager, and the HTTP/WS surface. */
export class Board {
  readonly app: Express
  readonly docs: DocService
  readonly sessions: SessionManager
  /** The ask lists on disk (spec-00005-FR-5): board state, kept outside git and outside docs/. */
  readonly asks: AskStore
  /**
   * The annotations of every document, and the unified submit that hands them to
   * the two paths that already exist (spec-00007): board state as well, beside
   * the ask lists and out of git.
   */
  readonly annotations: Annotations
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
    const { repoRoot, docsDir, config, spawn = spawnPty, spawnHeadless, awaitThresholdMs } = options
    this.repoRoot = repoRoot
    this.docs = new DocService(repoRoot, docsDir, config)
    this.asks = new AskStore(repoRoot)
    // The registry comes up empty, so a call the last process was killed with
    // is written off here rather than left saying «in progress» for good
    // (spec-00005-AC-5.3, design-00001 §10.2).
    this.asks.reconcile()
    // Everything written from outside the board arrives as this signal, so it is
    // also where the parsed tree goes stale (spec-00001 §7 非功能项).
    this.watcher = new DocsWatcher(docsDir, () => this.docs.invalidate())
    this.sessions = new SessionManager({
      agents: config.agents,
      maxSessions: config.maxSessions,
      repoRoot,
      spawn,
      spawnHeadless,
      snapshot: () => this.docs.snapshotDocs(),
      // The second, content-holding snapshot a cowrite session takes: its
      // collapse filter restores what it filters (design-00001 §11.3).
      contentSnapshot: () => this.docs.contentSnapshotDocs(),
      awaitThresholdMs,
      // A waiting mark going up or coming down is session state, and session
      // state reaches a board the one way all of it does: the refresh signal,
      // after which the board re-reads `GET /api/sessions` (spec-00001-FR-42,
      // spec-00003-FR-6). Through `signal` rather than a channel of its own, so
      // it folds into the same window as everything else (design-00001 §5).
      onAwaitingChange: () => this.watcher.signal(),
      // Every ask plan carries the thread it belongs to; the doc service builds
      // no other kind of ask plan (design-00001 §10.2).
      onAskEnd: (plan, result) => this.asks.finish(plan.sourceId, plan.threadId!, result),
      // Every end state of every session comes through here, and nearly none of
      // them is an issue batch (design-00001 §12.6). The refresh follows the
      // backfill rather than preceding it — both go through the signal's own
      // window, so a board reads the batch as it stands after it landed.
      onSessionEnd: async (info) => {
        try {
          await this.annotations.landBatch(info)
        } finally {
          // In a `finally`: a landing that failed is still an end the boards have
          // to hear about, and swallowing the refresh with it would leave every
          // page showing a session the server has finished with (issue-00013).
          this.watcher.signal()
        }
      },
      onExit: (plan, baseline) => this.finishSession(plan, baseline),
    })
    this.annotations = new Annotations({
      repoRoot,
      docs: this.docs,
      sessions: this.sessions,
      agents: config.agents,
      // The issue path calls the same cowrite ruling and the question path the
      // same ask receipt chain a hand-started one calls: «no difference in
      // behaviour» is one piece of code (spec-00007-FR-8, design-00001 §12.4).
      openAsk: (input) => this.openAsk(input),
    })
    // Same hook as the ask lists', one directory each: the registry comes up
    // empty, so a batch left reading `cowriting` is written off rather than shown
    // as being cowritten for ever (spec-00007-AC-10.8).
    this.annotations.store.reconcile()
    // The status lock of spec-00006-FR-10: the registry knows which documents are
    // being cowritten, the write paths are where the refusal belongs, and this is
    // the one wire between them (design-00001 §11.4).
    this.docs.attachCowriteProbe((docId) => this.sessions.cowriteOn(docId))
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
    // An ask makes no commit and never enters the commit queue
    // (spec-00005-FR-4): it wrote nothing, and whatever else is dirty under
    // docs/ belongs to the session that did write it, on that session's own
    // terms (spec-00005-AC-4.3). It still comes through here, because this is
    // where the refresh every session's end sends out is hung.
    if (plan.kind === 'ask') return { docId: plan.sourceId, problems: [], committed: false }
    // A cowrite commits through a filter of its own (spec-00006-FR-6 and FR-8):
    // only the target document and the well-formed new references land, and every
    // other change under docs/ is put back. The two readings only the registry can
    // take go in here — the claims of the sessions still running, whose products
    // are exempt from the restore, and the reference numbers they hold
    // (design-00001 §11.3). It never reaches the branch below: that one stages
    // whatever moved.
    if (plan.kind === 'cowrite') {
      const outcome = await this.docs.commitCowriteChanges(
        plan,
        before,
        this.sessions.runningClaims(),
        this.sessions.reservedNumbers(REFERENCE_TYPE),
      )
      // Spread whole rather than field by field: the commit's own hash rides
      // along, and an issue batch keeps it as its reference (spec-00007-AC-9.4).
      return { docId: plan.sourceId, ...outcome }
    }
    // Clarify and audit were asked for no new document, so there is nothing to
    // check: their commit is named by the kind and carries the document they
    // were about (spec-00001-AC-14.8, AC-50.3).
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
      const result = await this.docs.save(req.params.id, req.body.content, req.body.baseHash)
      // The hand-edit note (spec-00006-FR-5): after the save has landed, never
      // before — a refused save is no hand edit, and the note is about a change
      // the agent can go and read (design-00001 §11.4).
      this.sessions.noteHandEdit(req.params.id)
      res.json(result)
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
    // The two other terminal kinds: same channel, same concurrency rules
    // (spec-00003-FR-2, FR-3), each with its own ruling (FR-9, FR-51) made in
    // the doc service.
    app.post('/api/sessions/clarify', (req, res) => {
      res.json(this.sessions.start(this.docs.clarifyPlan(docIdOf(req.body)), agentOf(req.body)))
    })
    // The registry's second form (spec-00005-FR-1): a question, a headless call,
    // no terminal. The ask list of the document it was asked about is its own
    // resource — it outlives the document, so it does not hang under
    // `/api/docs/:id/` (design-00001 §7).
    app.post('/api/sessions/ask', async (req, res) => res.json(await this.askSession(req.body)))
    app.get('/api/asks/:id', (req, res) => res.json({ threads: this.asks.read(req.params.id).threads }))
    // The annotations of one document, and the unified submit of them
    // (spec-00007-FR-3, FR-5). Not under `/api/docs/:id/`: the annotations
    // outlive the document, so they are addressable after it is gone, the way the
    // ask list is (design-00001 §12.3).
    app.get('/api/annotations/:id', (req, res) => res.json(this.annotations.list(req.params.id)))
    app.post('/api/annotations/:id', async (req, res) => {
      res.status(201).json({ annotation: await this.annotations.add(req.params.id, req.body) })
    })
    app.patch('/api/annotations/:id/:annotationId', async (req, res) => {
      const annotation = await this.annotations.change(req.params.id, req.params.annotationId, req.body)
      res.json({ annotation })
    })
    app.delete('/api/annotations/:id/:annotationId', async (req, res) => {
      await this.annotations.remove(req.params.id, req.params.annotationId)
      res.json({ annotationId: req.params.annotationId })
    })
    app.post('/api/annotations/:id/submit', async (req, res) => {
      res.json(await this.annotations.submit(req.params.id, req.body))
    })
    app.post('/api/sessions/audit', (req, res) => {
      res.json(this.sessions.start(this.docs.auditPlan(docIdOf(req.body)), agentOf(req.body)))
    })
    // The fifth kind, on one entry in two forms (design-00001 §11.2): a document
    // that is already on disk, or one this very request files first
    // (spec-00006-FR-1, FR-2).
    app.post('/api/sessions/cowrite', async (req, res) => res.json(await this.cowriteSession(req.body)))
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

  /**
   * One question on one document (spec-00005-FR-1 and FR-2). No `threadId` opens
   * a new thread — a headless first call; one that names a thread is its
   * follow-up, or the resend of a question that failed or was stopped, and the
   * form follows the thread's resume id (design-00001 §10.2).
   *
   * The order is that section's receipt chain: the document, then the thread's
   * own serial rule, then the cap and the registry slot, then the record on
   * disk, and only then the call itself — a refusal anywhere along it writes
   * nothing (spec-00005-AC-6.4, AC-7.1).
   */
  private async askSession(body: unknown): Promise<{ sessionId: string; threadId: string }> {
    return this.openAsk(askRequest(body))
  }

  /**
   * The receipt chain itself, without the request around it: what the ask entry
   * runs, and what each question of a unified submit runs (design-00001 §12.5).
   * One function, so the two entries cannot receive a question differently.
   */
  private async openAsk(input: {
    docId: string
    question: string
    threadId?: string
    resend?: boolean
    agent?: string
    /** The passage a submitted question was marked on; a typed one carries none. */
    selection?: SelectionAnchor
  }): Promise<{ sessionId: string; threadId: string }> {
    const { docId, question, threadId, resend, agent, selection } = input
    // Held so a failure *after* the admission can be undone: the slot is taken
    // inside the callback and the record is written after it, so a write that
    // fails would otherwise leave a session running with no process to come
    // (design-00001 §10.2 写序).
    let admitted: string | undefined
    /** The thread the record went on, once it has: what a rollback has to land. */
    let opened: string | undefined
    try {
      const started = await this.asks.open(docId, { question, threadId, resend }, (thread) => {
        const info = this.sessions.start(
          this.docs.askPlan(docId, question, thread, selection),
          // A follow-up runs the agent its thread was opened with: a resume id
          // is that one CLI's, and no other could take it (spec-00005-FR-2).
          thread.exchanges.length === 0 ? agent : thread.agent,
        )
        admitted = info.id
        opened = thread.id
        return info
      })
      this.sessions.launch(started.admitted.id)
      return { sessionId: started.admitted.id, threadId: started.thread.id }
    } catch (cause) {
      const message = (cause as Error).message
      if (admitted) this.sessions.abandon(admitted, message)
      // The record is written after the slot is taken, so a failure past that
      // point leaves the question `running` on disk with nothing to answer it —
      // and a thread with a running question refuses every submit, so it would
      // be shut until a restart reconciled it (spec-00005-FR-7, AC-5.3). It
      // lands `failed` here instead, resendable at once. `resumed: false`
      // deliberately: no CLI was ever asked, so nothing was refused and the
      // thread's continuation is not in doubt (design-00001 §10.2).
      if (admitted && opened !== undefined) {
        await this.asks.finish(docId, opened, { outcome: 'failed', resumed: false, reason: message })
      }
      throw cause
    }
  }

  /**
   * One cowrite session, on the document the request names or on the one it asks
   * to be created (spec-00006-FR-1 and FR-2, design-00001 §11.2).
   *
   * The create form's order is that section's ruling — the slot **before** the
   * file: the three create rejections and the agent are judged first, on nothing
   * but the graph; the slot is taken next, so a cap refusal has written nothing
   * (spec-00006-AC-2.6) and has taken nothing that outlives it (AC-2.7); the
   * document is filed after that; and the process starts last. A write that fails
   * gives the slot back at once, and a **commit** that fails keeps the file and
   * rides along as an error — the document is on disk, so the session goes ahead
   * (spec-00001-FR-20).
   */
  private async cowriteSession(body: unknown): Promise<{ sessionId: string; docId: string; error?: string }> {
    const { docId, create, agent, materials } = cowriteRequest(body)
    const reserved = this.sessions.reservedNumbers(REFERENCE_TYPE)
    if (docId !== undefined) {
      const info = this.sessions.start(this.docs.cowritePlan(docId, materials, reserved), agent)
      return { sessionId: info.id, docId }
    }
    const created = this.docs.cowriteCreatePlan(create!.type, create!.slug, materials, reserved)
    const info = this.sessions.startDeferred(created.plan, agent)
    let commit: ActionResult
    try {
      // The target the plan already allocated, threaded rather than worked out
      // again: the document the session was admitted on is the document that
      // lands (design-00001 §11.2).
      commit = await this.docs.createForCowrite({ id: created.docId, path: created.path, type: create!.type })
    } catch (cause) {
      // Nothing was filed, so nothing is left running on it: the slot goes back
      // and the refusal is the caller's answer (spec-00006-FR-2's all or nothing).
      this.sessions.abandon(info.id, (cause as Error).message)
      throw cause
    }
    this.sessions.launchTerminal(info.id)
    return { sessionId: info.id, docId: created.docId, ...(commit.error === undefined ? {} : { error: commit.error }) }
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

  /**
   * A normal shutdown, as the entry point runs it on SIGINT/SIGTERM: every
   * running session is wrapped up the way a stop wraps one up — history written
   * and commit made — and only then is the process let go (spec-00003-FR-9,
   * spec-00003-AC-9.3). Idempotent, so a second signal joins the shutdown
   * already running.
   */
  async shutdown(): Promise<void> {
    await this.sessions.shutdown()
  }
}

/** A clarify or audit request names the one document it is about. */
function docIdOf(body: { docId?: string }): string {
  if (typeof body.docId !== 'string') {
    throw new WorkflowError('a clarify or audit session needs a docId')
  }
  return body.docId
}

/**
 * What an ask request has to carry (design-00001 §7): the document, the question
 * — an empty one is nothing to ask, and is refused rather than sent
 * (spec-00005-FR-7) — and, when it is a follow-up or a resend, the thread it
 * belongs to. `resend` is what tells those two apart: only a resend rewrites the
 * question it is resending, and the record alone cannot say which was meant.
 */
function askRequest(body: unknown): {
  docId: string
  question: string
  threadId?: string
  resend?: boolean
  agent?: string
} {
  const { docId, question, threadId, resend } = (body ?? {}) as {
    docId?: unknown
    question?: unknown
    threadId?: unknown
    resend?: unknown
  }
  if (typeof docId !== 'string') {
    throw new WorkflowError('an ask needs a docId')
  }
  if (typeof question !== 'string' || question.trim() === '') {
    throw new WorkflowError('an ask needs a question to put to the agent')
  }
  if (threadId !== undefined && typeof threadId !== 'string') {
    throw new WorkflowError('threadId must name a thread of that document’s ask list')
  }
  if (resend !== undefined && typeof resend !== 'boolean') {
    throw new WorkflowError('resend says whether this question replaces the last one, so it is true or false')
  }
  return { docId, question, threadId, resend, agent: agentOf(body as { agent?: unknown }) }
}

/**
 * What a cowrite request has to carry (design-00001 §11.2): the document it is
 * about, **or** the type and slug of one to be created — the two are exclusive,
 * and neither or both is no request at all. `materials` is optional and is
 * shape-checked here, so nothing that is not text, ids, paths or URLs reaches the
 * instruction (spec-00006-FR-3).
 */
function cowriteRequest(body: unknown): {
  docId?: string
  create?: { type: string; slug: string }
  agent?: string
  materials?: CowriteMaterials
} {
  const { docId, create, materials } = (body ?? {}) as { docId?: unknown; create?: unknown; materials?: unknown }
  if (docId !== undefined && typeof docId !== 'string') {
    throw new WorkflowError('docId names the document to cowrite')
  }
  if ((docId === undefined) === (create === undefined)) {
    throw new WorkflowError('a cowrite names either the docId to write, or the create to file first — one of the two')
  }
  return {
    docId,
    create: create === undefined ? undefined : createOf(create),
    agent: agentOf(body as { agent?: unknown }),
    materials: materialsOf(materials),
  }
}

/** The type and slug the create form gives (spec-00006-FR-2); the number is the board's. */
function createOf(value: unknown): { type: string; slug: string } {
  const { type, slug } = (value ?? {}) as { type?: unknown; slug?: unknown }
  if (typeof type !== 'string' || typeof slug !== 'string') {
    throw new WorkflowError('create needs the type of document to file and the slug of its id')
  }
  return { type, slug }
}

/**
 * The materials, as the four fields of design-00001 §11.1 and nothing else: the
 * pasted text a string, the three lists lists of strings. Anything else is
 * refused rather than dropped — a material the agent will never be told about is
 * worse than a refusal that says so.
 */
function materialsOf(value: unknown): CowriteMaterials | undefined {
  if (value === undefined || value === null) return undefined
  const { text, docIds, paths, urls } = (value ?? {}) as Record<string, unknown>
  if (text !== undefined && typeof text !== 'string') {
    throw new WorkflowError('materials.text is the text the owner pasted, as one string')
  }
  const lists = { docIds, paths, urls }
  for (const [field, list] of Object.entries(lists)) {
    if (list !== undefined && (!Array.isArray(list) || list.some((item) => typeof item !== 'string'))) {
      throw new WorkflowError(`materials.${field} must be a list of strings`)
    }
  }
  return {
    text,
    docIds: docIds as string[] | undefined,
    paths: paths as string[] | undefined,
    urls: urls as string[] | undefined,
  }
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
  [AskBusyError, 409],
  [AnnotationConflictError, 409],
  [NoSessionError, 404],
  [NoAnnotationError, 404],
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

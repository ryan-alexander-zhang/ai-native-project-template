import { useCallback, useEffect, useRef, useState } from 'react'
import { toast } from 'sonner'
import type { DocKind, FlowStep } from '../../src/config.ts'
import type { DocGraph } from '../../src/docRepository.ts'
import { type ItemsView, declaresItems } from '../../src/requirements.ts'
import { SUBMIT_REFUSAL } from './annotationRows.ts'
import {
  ApiError,
  type AnnotationChange,
  type AnnotationInput,
  type AnnotationListView,
  type AskSubmit,
  type AskThread,
  type CoverageRow,
  type CowriteSubmit,
  type DocContent,
  type EffectiveAgent,
  type SessionInfo,
  type SessionListing,
  api,
} from './api.ts'
import type { EditorMode } from './Editor.tsx'
import { connectEvents } from './eventSocket.ts'
import { prefillFrontMatter } from './frontMatter.ts'
import { type Placed, layoutGraph } from './layout.ts'
import { useDesktopNotifications } from './notify.ts'

const EMPTY_GRAPH: DocGraph = { nodes: [], edges: [], issues: [], diagnostics: [], idOwners: {} }

/**
 * How many gap ids a refusal toast names. A plan can deliver dozens of items,
 * and a toast listing every one of them is a wall the count cannot be read off
 * — so the list is cut and the count, which is the number the user acts on,
 * is kept whole (design-00002 §3).
 */
const GAPS_NAMED = 5

/** The cap the board assumes until `GET /api/config` says otherwise (spec-00003-AC-3.5). */
const DEFAULT_MAX_SESSIONS = 3

/** A session is over once it is any of these, whichever way it got there (spec-00003-FR-7). */
function ended(session: SessionListing): boolean {
  return session.status !== 'running'
}

/**
 * The running ones, in start order. Awaiting input is a reading of a running
 * session, not a state of its own, so it counts here too (spec-00003-FR-6).
 */
function runningOf(sessions: SessionListing[]): SessionListing[] {
  return sessions.filter((session) => !ended(session))
}

/**
 * What a refusal reads as. The `resolved` gate names its gaps one by one
 * (spec-00001-FR-52) and the toast is that list's presentation: the count
 * first, then as many ids as fit (design-00002 §3). Every other refusal is its
 * own message and is passed through untouched.
 */
function refusalText(error: unknown): string {
  if (error instanceof ApiError && error.gaps !== undefined) {
    const named = error.gaps.slice(0, GAPS_NAMED)
    const rest = error.gaps.length - named.length
    const tail = rest === 0 ? '' : `, and ${rest} more`
    return `${error.gaps.length} items unverified: ${named.join(', ')}${tail}`
  }
  return error instanceof Error ? error.message : String(error)
}

/**
 * Board state: what is on the canvas, what is selected, and which panels are
 * open. `openSession` is what a clicked desktop notification does — the session
 * panel row's own act, which the board owns because half of it is the canvas
 * moving (spec-00004-FR-5).
 */
export function useBoard(openSession: (session: SessionListing) => void) {
  const [graph, setGraph] = useState<DocGraph>(EMPTY_GRAPH)
  const [placed, setPlaced] = useState<Placed[]>([])
  const [kinds, setKinds] = useState<Record<string, DocKind>>({})
  // Relation field order drives the relation list's grouping (spec-00001-FR-30).
  const [relationOrder, setRelationOrder] = useState<string[]>([])
  // Which types may be clarified and which audited are the payload's answer,
  // never the board's: the entries follow the sets `GET /api/config` sends, so a
  // set that changes there changes what is on show here and the two cannot drift
  // (spec-00001-FR-56, AC-56.2).
  const [clarifiable, setClarifiable] = useState<string[]>([])
  const [auditable, setAuditable] = useState<string[]>([])
  // The types a document may be created at (spec-00001-FR-53); none means no
  // create entry at all (spec-00001-AC-53.6).
  const [entry, setEntry] = useState<string[]>([])
  // The agents a session may run under, and the one the next session will use.
  // A single agent is left unnamed: the request then carries no agent field and
  // the server takes the first, as it always did (spec-00001-AC-55.4).
  const [agents, setAgents] = useState<string[]>([])
  // Of those, the ones that declare a headless form: the whole of an ask's
  // choice, and — when it is empty — the reason neither ask entry is drawn
  // (spec-00005-FR-2, AC-7.4). It is read off the same payload the other entries
  // are, so the board keeps no ruling of its own (spec-00001-FR-56).
  const [askAgents, setAskAgents] = useState<string[]>([])
  const [agent, setAgent] = useState<string>()
  const [selected, setSelected] = useState<string>()
  // Carried with the document it was read for, so a panel never shows the
  // previous selection's items while the next ones are in flight.
  const [items, setItems] = useState<{ docId: string; view: ItemsView }>()
  const [transitions, setTransitions] = useState<string[]>([])
  const [nextSteps, setNextSteps] = useState<FlowStep[]>([])
  // Editor and terminal are independent: an agent can work while a document is open.
  const [editing, setEditing] = useState<string>()
  // The prefilled buffer of a document that is not on disk yet: set together
  // with `editing`, and what tells the editor that saving creates rather than
  // revises (spec-00001-FR-53).
  const [draft, setDraft] = useState<string>()
  /**
   * The editor's view state and the thread the ask list is located on — both
   * presentation state of the board rather than of the editor, since a panel row
   * and a desktop notification set them from outside (design-00002 §10, §14).
   * The threads themselves are the payload those two are resolved against.
   */
  const [editorMode, setEditorMode] = useState<EditorMode>('source')
  const [located, setLocated] = useState<string>()
  const [threads, setThreads] = useState<AskThread[]>([])
  /**
   * The annotations of the document in the editor, and — beside them and held by
   * id like every other presentation state — the one the board is located on
   * (design-00002 §16.8). The two locate items never disturb each other: going
   * to a thread leaves the located annotation where it is.
   */
  const [annotations, setAnnotations] = useState<{ docId: string; view: AnnotationListView }>()
  const [locatedAnnotation, setLocatedAnnotation] = useState<string>()
  /**
   * Which locate this is. The located annotation alone is not enough to act on:
   * asking for the same one twice is two asks — and a change of the document
   * clears the mark, so the second ask is exactly how a reader gets it back
   * (design-00002 §16.6).
   */
  const [locatedAt, setLocatedAt] = useState(0)
  /**
   * The annotation waiting for a new selection. **Not** presentation state
   * (design-00002 §16.4): it is a gesture under way, and one kept across a
   * refresh would leave the user being asked to select a passage somewhere they
   * never asked to be.
   */
  const [reanchoring, setReanchoring] = useState<string>()
  /** Whether the editor buffer holds unsaved edits, as the editor reports it. */
  const [unsavedBuffer, setUnsavedBuffer] = useState(false)
  /** Whether a unified submit is on its way out (design-00002 §16.5's pending entry). */
  const [submitting, setSubmitting] = useState(false)
  const [terminalOpen, setTerminalOpen] = useState(false)
  // Every session the server holds, and — apart from it — which one the terminal
  // is showing: the pick is presentation state, kept by session id across a
  // refresh (spec-00003-FR-5, design-00002 §10).
  const [sessions, setSessions] = useState<SessionListing[]>([])
  const [shownId, setShownId] = useState<string>()
  // What «N/limit» divides by (spec-00003-FR-4). The config is the single source
  // of the cap; until it lands the default is the one the server would use.
  const [maxSessions, setMaxSessions] = useState(DEFAULT_MAX_SESSIONS)
  // The global coverage view (spec-00002-FR-10): whether it is on show, and the
  // payload it is showing. Undefined is «not read yet», which is what the first
  // moment after opening looks like.
  const [coverageOpen, setCoverageOpen] = useState(false)
  const [coverage, setCoverage] = useState<CoverageRow[]>()
  /**
   * What the cowrite target says on disk, re-read with each refresh while its
   * editor is open — the fifth conditional read of the one refresh path
   * (design-00002 §10). Carried with the document it was read for, so another
   * document's editor is never handed it; what the editor does with it is
   * spec-00006-FR-4's, not this hook's.
   */
  const [disk, setDisk] = useState<{ docId: string; content: DocContent }>()

  // Column order comes from the config; the layout is meaningless without it.
  const typeOrder = useRef<string[]>([])
  // The same flag as `coverageOpen`, readable from `refresh` without making the
  // callback depend on it: a `refresh` rebuilt on every open would tear the
  // docs-change channel down and dial it again (design-00002 §10).
  const viewing = useRef(false)
  /**
   * The document whose ask list is open, readable from `refresh` for the same
   * reason `viewing` is a ref. It is what makes the list the fourth item of the
   * one refresh path: while it is open the threads are re-read with the graph,
   * and while it is not, nothing is asked for — without it `running → answered`
   * would never reach the page (spec-00005-AC-3.3, design-00001 §10.3).
   */
  const listed = useRef<string | undefined>(undefined)
  /**
   * The cowrite target whose editor is open — the whole condition of the fifth
   * read, and a ref for the same reason `viewing` is one: a `refresh` rebuilt
   * whenever it changed would tear the docs-change channel down and dial it
   * again (design-00002 §10, §15).
   */
  const cowriting = useRef<string | undefined>(undefined)
  /**
   * The document whose **editor** is open — the whole condition of the sixth
   * read, and a ref for the same reason the others are. Deliberately not «whose
   * annotation list is open», unlike the four reads above: the traces have to be
   * right in the editing and preview states too, and a list-only condition would
   * leave them stale in the two states they are actually drawn in
   * (design-00002 §16.8).
   */
  const annotating = useRef<string | undefined>(undefined)
  /**
   * The last of the two body states this editor was on, which is where a locate
   * and the re-anchor mode land. Editing when there is no record
   * (design-00002 §16.4, §16.6).
   */
  const bodyMode = useRef<EditorMode>('source')
  /**
   * How each session was last seen: the status it was in, and whether it was
   * waiting. The refresh signal is the only channel either reaches the board
   * through (design-00001 §5), so both are differences between two readings of
   * the listing rather than events of their own — which is what the toasts
   * (spec-00003-FR-7) and the desktop notifications (spec-00004-FR-2, FR-3) are
   * derived from. `undefined` is «nothing read yet»: the first reading is the
   * baseline and announces nothing, or a board opened after a session ended
   * would report it as news.
   */
  const seen = useRef<Map<string, { status: string; awaiting: boolean }> | undefined>(undefined)
  /**
   * The session on show, readable from `refresh` without making the callback
   * depend on it — the same reason `viewing` is a ref: a `refresh` rebuilt on
   * every switch would tear the docs-change channel down and dial it again.
   */
  const shownRef = useRef<string | undefined>(undefined)
  /**
   * The read in flight, so the next one can queue behind it. Two reads at once
   * fold their listings into `seen` in whatever order the responses land, and
   * waiting is not a state a session climbs to and stays in the way a status is:
   * a «not waiting» reading applied after the «waiting» reading it came before
   * loses that turn, and a session that then sits waiting never turns again, so
   * nobody is ever told (issue-00018). Ordered reads are the whole of the fix —
   * the diff below is right as long as it sees every reading, in order.
   */
  const reading = useRef<Promise<unknown>>(Promise.resolve())

  // The desktop side of the same two events (spec-00004): it is fed from the
  // diff below and posts nothing while the user is looking at the board.
  const notifications = useDesktopNotifications(sessions, openSession)

  /**
   * One toast per session that has just reached an end state, stacked and never
   * folded together (spec-00003-FR-7). A session that appears already ended was
   * never running here — a start that failed on the spawn (spec-00001-FR-16) —
   * and is announced the same way (spec-00003-AC-7.4).
   *
   * The same diff carries the waiting turns (design-00002 §13): «not waiting →
   * waiting» is reported as a turn; whether it is owed a desktop notification
   * is notify.ts's per-away-stint judgment — at most one per session per stint
   * (spec-00004-FR-2, issue-00020). Waiting being lifted is the user's own
   * doing and says nothing (spec-00004-AC-2.2).
   */
  const announce = useCallback(
    (listing: SessionListing[]) => {
      const before = seen.current
      seen.current = new Map(
        listing.map((session) => [session.id, { status: session.status, awaiting: session.awaiting === true }]),
      )
      if (before === undefined) return
      for (const session of listing) {
        const was = before.get(session.id)
        if (ended(session) && was?.status !== session.status) {
          toast.message(`${session.kind} · ${session.sourceId}`, { description: session.status })
          notifications.ended(session)
        }
        if (!ended(session) && session.awaiting === true && was?.awaiting !== true) {
          notifications.waiting(session)
        }
      }
    },
    [notifications.ended, notifications.waiting],
  )

  /** The coverage payload, re-read (spec-00002-AC-10.4). A failure is the toast every read gets. */
  const readCoverage = useCallback(async () => {
    try {
      setCoverage(await api.coverage())
    } catch (error) {
      toast.error(error instanceof Error ? error.message : String(error))
    }
  }, [])

  /**
   * Open or close the coverage view. Closing lets the payload go: the view is
   * re-read from scratch when it comes back, so nothing stale is ever on show.
   */
  const showCoverage = useCallback(
    (open: boolean) => {
      viewing.current = open
      setCoverageOpen(open)
      if (open) void readCoverage()
      else setCoverage(undefined)
    },
    [readCoverage],
  )

  /**
   * The open ask list, re-read (spec-00005-AC-3.3). The location follows the
   * same close-nearest rule everything else does: a thread that has gone from
   * the payload takes only the location with it, and the list state stays
   * (design-00002 §10).
   */
  const readAsks = useCallback(async (): Promise<AskThread[]> => {
    const docId = listed.current
    if (docId === undefined) return []
    try {
      const next = await api.asks(docId)
      // The answer is only good for the list it was asked about. Two reads can be
      // in flight — a refresh's and a switch's — and a slow one landing last
      // would paint another document's threads, or the closed list's
      // (design-00002 §10: the list is read while it is open, and this is which
      // list).
      if (listed.current !== docId) return []
      setThreads(next)
      setLocated((current) =>
        current !== undefined && next.some((thread) => thread.id === current) ? current : undefined,
      )
      return next
    } catch (error) {
      // A list that could not be read is a list nobody may be shown: leaving the
      // last document's threads painted under this document's name is worse than
      // an empty list with the reason said out loud.
      if (listed.current === docId) {
        setThreads([])
        setLocated(undefined)
      }
      toast.error(error instanceof Error ? error.message : String(error))
      return []
    }
  }, [])

  /**
   * The open editor's annotations, re-read (design-00002 §16.8's sixth item):
   * the records, where each anchor lands on the disk just now, the batches, and
   * the submit statement. The location follows the same close-nearest rule
   * everything else does — an annotation that has gone from the payload takes
   * only the location with it and the list state stays.
   */
  const readAnnotations = useCallback(async () => {
    const docId = annotating.current
    if (docId === undefined) return
    try {
      const view = await api.annotations(docId)
      // Only good for the editor it was asked about, for the reason the ask
      // list's read is: two reads can be in flight and the slow one must not
      // paint another document's annotations.
      if (annotating.current !== docId) return
      setAnnotations({ docId, view })
      setLocatedAnnotation((current) =>
        current !== undefined && view.annotations.some((one) => one.id === current) ? current : undefined,
      )
    } catch (error) {
      if (annotating.current === docId) {
        setAnnotations(undefined)
        setLocatedAnnotation(undefined)
      }
      toast.error(error instanceof Error ? error.message : String(error))
    }
  }, [])

  /**
   * Everything that belonged to the document the editor was on. **One** list of
   * it, because every way into an editor goes through it — opening a document,
   * going to a thread, starting a cowrite — and a way in that forgot one item
   * would carry that item into the next document: a re-anchor gesture, say, which
   * would take the right-click away there and point the first selection made at an
   * annotation of the document that was closed (design-00002 §14, §16.4).
   */
  const forget = useCallback((id?: string) => {
    setLocated(undefined)
    setThreads([])
    listed.current = undefined
    bodyMode.current = 'source'
    annotating.current = id
    setAnnotations(undefined)
    setLocatedAnnotation(undefined)
    setReanchoring(undefined)
    setUnsavedBuffer(false)
  }, [])

  /**
   * The cowrite target's text on disk (spec-00006-FR-4). Asked for only while
   * that document's editor is open, like the coverage view's read and the ask
   * list's: not in a cowrite, nothing is asked for.
   *
   * A target that has left the graph is the close-nearest case of design-00002
   * §10: its editor goes and nothing else does — the session is still in the
   * registry and its terminal, with the stop in its header, stays exactly where
   * it was.
   */
  const readCowriteTarget = useCallback(async (next: DocGraph) => {
    const docId = cowriting.current
    if (docId === undefined) return
    if (!next.nodes.some((node) => node.id === docId)) {
      setEditing(undefined)
      setDisk(undefined)
      return
    }
    try {
      setDisk({ docId, content: await api.doc(docId) })
    } catch (error) {
      // The buffer is left alone: a read that failed is a reload that did not
      // happen, and the reason is worth saying out loud (design-00002 §15).
      toast.error(error instanceof Error ? error.message : String(error))
    }
  }, [])

  /**
   * The one way the board takes the docs in again (spec-00001-FR-44): all three
   * triggers — a push, an action of the board's own, the end of a session — come
   * through here, so what is kept and what is let go of cannot differ between
   * them (design-00002 §10). The items of the document on show follow the graph
   * through the effect below.
   *
   * The session listing is re-read with the graph, not just at load: a session
   * that ended is exactly what a refresh may have been sent to tell us about,
   * and the counts, the markers, the entries and the stop all hang off it
   * (issue-00013). Every session comes back, running and ended alike
   * (spec-00003-FR-4); which one the terminal shows is decided here and nowhere
   * else, so a refresh keeps the user on the session they were on
   * (spec-00003-AC-5.6).
   */
  const read = useCallback(async () => {
    const [next, listing] = await Promise.all([api.graph(), api.sessions()])
    const first = seen.current === undefined
    announce(listing)
    setGraph(next)
    setPlaced(layoutGraph(next, typeOrder.current))
    setSessions(listing)
    if (first) {
      // Nothing has been shown yet, so the board picks: the newest running
      // session — the one a reconnecting board reattaches to — or, with nothing
      // running, the newest there was, so the panel still says how it ended
      // (spec-00003-FR-9). Only a running one brings the terminal up with it.
      // An ask is never picked, however new it is: it has no pty and the
      // terminal is not what it is read in (spec-00005-AC-3.4, AC-3.5).
      const shown = listing.filter((one) => one.kind !== 'ask')
      const running = runningOf(shown)
      const pick = (running.length > 0 ? running : shown).at(-1)
      if (pick !== undefined) {
        shownRef.current = pick.id
        setShownId(pick.id)
        if (!ended(pick)) setTerminalOpen(true)
      }
    } else if (shownRef.current !== undefined && !listing.some((one) => one.id === shownRef.current)) {
      // Close nearest: the session on show has gone from the listing, so the
      // terminal view of it goes and nothing else does (design-00002 §10).
      shownRef.current = undefined
      setShownId(undefined)
      setTerminalOpen(false)
    }
    // The selection is held by id, never by position: a document still on the
    // board keeps it, and one that has left the disk takes it with it, closing
    // its toolbar (spec-00001-AC-44.6).
    setSelected((current) =>
      current !== undefined && next.nodes.some((node) => node.id === current) ? current : undefined,
    )
    // The third payload of the one refresh path, and only while somebody is
    // looking at it: the coverage view has no refresh of its own, and the read
    // is the heaviest the board makes (design-00001 §6, spec-00002-AC-10.4).
    if (viewing.current) await readCoverage()
    // And the fourth, on the same terms: the ask list is re-read while it is
    // open and not otherwise (spec-00005-AC-3.3, design-00002 §10).
    await readAsks()
    // And the fifth, on the same terms again: while a cowrite session's target
    // is open in the editor, its text on disk comes with the graph, and the
    // editor decides what to do with it — reload a clean buffer, keep a dirty
    // one (spec-00006-FR-4, design-00002 §10, §15).
    await readCowriteTarget(next)
    // And the sixth, on terms of its own: the annotations come with the graph
    // while that document's **editor** is open, in whichever view state
    // (design-00002 §16.8).
    await readAnnotations()
    return next
  }, [readCoverage, readAsks, readCowriteTarget, readAnnotations])

  /**
   * The one way in, and one read at a time (see `reading` above). A read that
   * failed still lets the next one start: the queue carries the turn, not the
   * answer.
   */
  const refresh = useCallback((): Promise<DocGraph> => {
    const next = reading.current.then(read)
    reading.current = next.catch(() => undefined)
    return next
  }, [read])

  /** Hold a document as the selection and read what its toolbar offers. */
  const load = useCallback(async (id: string) => {
    setSelected(id)
    const [nextTransitions, steps] = await Promise.all([api.transitions(id), api.nextSteps(id)])
    setTransitions(nextTransitions)
    setNextSteps(steps)
  }, [])

  const select = useCallback(
    async (id: string) => {
      // Selecting is only meaningful for a document that is on the board. The
      // relation list can offer a broken link's target, which is not
      // (issue-00005) — refuse here, where the invariant belongs, rather than
      // at each call site.
      if (!graph.nodes.some((node) => node.id === id)) {
        toast.error(`no document ${id} on the board`)
        return
      }
      await load(id)
    },
    [graph, load],
  )

  const deselect = useCallback(() => setSelected(undefined), [])

  /** Run a board action, refresh the graph, and surface a refusal as a toast. */
  const run = useCallback(
    async (action: () => Promise<unknown>) => {
      try {
        await action()
        await refresh()
      } catch (error) {
        toast.error(refusalText(error))
      }
    },
    [refresh],
  )

  /**
   * The one way a session opens, whichever kind it is: the started session is the
   * one the terminal comes up on (spec-00003-AC-5.4). A refusal — the document
   * already has a session, the cap is reached, the document is gone — leaves both
   * alone (spec-00003-FR-2, FR-3).
   */
  const startSession = useCallback(
    async (start: () => Promise<SessionInfo>) => {
      await run(async () => {
        const started = await start()
        shownRef.current = started.id
        setShownId(started.id)
        setTerminalOpen(true)
      })
    },
    [run],
  )

  /**
   * Open a document's ask list, and — when the call that led here is named —
   * located on the thread that call belongs to. The lookup is by
   * `runSessionId`: a panel row and a notification hold the registry session id
   * and nothing else, so the thread is found through the list itself
   * (design-00001 §10.3).
   */
  const showAsks = useCallback(
    async (docId: string, runSessionId?: string) => {
      setDraft(undefined)
      setEditing(docId)
      setEditorMode('asks')
      // Nothing of the last document's editor survives the switch: it is all held
      // by document id, and anything painted under another document's name would
      // be read as this one's (design-00002 §10, §16.4).
      forget(docId)
      listed.current = docId
      void readAnnotations()
      const next = await readAsks()
      if (runSessionId === undefined) return
      setLocated(next.find((thread) => thread.exchanges.some((one) => one.runSessionId === runSessionId))?.id)
    },
    [readAsks, readAnnotations, forget],
  )

  /**
   * Go to a session (spec-00003-FR-4, FR-10) — and the way there forks on the
   * kind (spec-00005-FR-9): a terminal-form session comes up on the terminal,
   * which brings the panel back with it and shows an ended one too, since its
   * output is still worth reading; an ask has no terminal at all, and leads to
   * its document's ask list located on the thread it answered.
   *
   * What comes back is whether the board should also go to the document, which
   * only the terminal path wants: the ask path has said its own piece when it
   * refuses — the document is not on the board, so there is no editor for the
   * list to live in and nothing moves (spec-00005-AC-9.4, AC-9.5).
   */
  const showSession = useCallback(
    (id: string): boolean => {
      const session = sessions.find((one) => one.id === id)
      if (session === undefined) {
        toast.error(`no session ${id} on the board`)
        return false
      }
      if (session.kind !== 'ask') {
        shownRef.current = id
        setShownId(id)
        setTerminalOpen(true)
        return true
      }
      if (!graph.nodes.some((node) => node.id === session.sourceId)) {
        toast.error(`no document ${session.sourceId} on the board`)
        return false
      }
      void showAsks(session.sourceId, session.id)
      return false
    },
    [sessions, graph, showAsks],
  )

  /**
   * Which of the editor's three views is on show (spec-00005-FR-9). The ask list
   * is the only one with a payload behind it, so opening it is what puts the
   * fourth read on the refresh path and closing it is what takes it off again.
   */
  const showEditorMode = useCallback(
    (docId: string, mode: EditorMode) => {
      setEditorMode(mode)
      // The threads are read while **either** list is open: an annotation's
      // question state is the last exchange of its thread, so stopping on the
      // annotation list without them would leave every question row stale
      // (design-00002 §16.8).
      listed.current = mode === 'asks' || mode === 'annotations' ? docId : undefined
      if (mode === 'source' || mode === 'preview') bodyMode.current = mode
      if (listed.current !== undefined) void readAsks()
    },
    [readAsks],
  )

  /**
   * One question (spec-00005-FR-1): a new thread, a follow-up on one, or a
   * failed question put again. Nothing else happens — no terminal comes up
   * (FR-3) — and the refresh that follows brings the running call onto the list
   * and the node's marker.
   *
   * What comes back is whether the question went. Every refusal is a toast like
   * any other action's, but the input that sent it also has to know: it is
   * holding the user's words, and words thrown away on a refusal are words typed
   * twice (spec-00005-FR-7).
   */
  const ask = useCallback(
    async (submit: AskSubmit): Promise<boolean> => {
      try {
        await api.ask(submit)
      } catch (error) {
        toast.error(refusalText(error))
        return false
      }
      await refresh()
      return true
    },
    [refresh],
  )

  /**
   * One cowrite launch, in either of its forms (spec-00006-FR-1, FR-2): on a
   * document already on disk, or on one the server files first — which is why the
   * document the workspace opens on comes back with the answer rather than being
   * assumed here.
   *
   * Success is the workspace of design-00002 §15: the target's editor, its view
   * state forced to Source over whatever that document was last left on, and the
   * terminal switched to this session — the deliberate exception to «only the
   * first session presents itself», since AC-4.1 asks for both on screen at once.
   *
   * What comes back is whether it went: every refusal is a toast, and the input
   * that sent it also has to know, because it is holding materials that would
   * otherwise have to be gathered twice (spec-00006-FR-9, AC-9.1).
   */
  const cowrite = useCallback(
    async (submit: CowriteSubmit): Promise<boolean> => {
      let started: { sessionId: string; docId: string; error?: string }
      try {
        started = await api.cowrite(submit)
      } catch (error) {
        toast.error(refusalText(error))
        return false
      }
      // The create form alone: the document is filed and the session is away,
      // and only its commit failed — the file is kept and the failure is a
      // notice, not a refusal (spec-00001-FR-20, design-00001 §11.2).
      if (started.error !== undefined) toast.error(started.error)
      setDraft(undefined)
      setEditing(started.docId)
      setEditorMode('source')
      forget(started.docId)
      shownRef.current = started.sessionId
      setShownId(started.sessionId)
      setTerminalOpen(true)
      await refresh()
      return true
    },
    [refresh],
  )

  /**
   * One new annotation (spec-00007-FR-1). What comes back is its id — the trace of
   * an annotation made on an **unsaved** buffer is put into the local interval set
   * under it, since no refresh will bring it back for a text only the browser
   * holds (spec-00007-AC-1.3) — or `undefined` when it was refused, which keeps
   * the words that were typed where they are.
   *
   * The document is **named by the caller** rather than read off the open editor:
   * the entry belongs to a passage of one document, and a request that resolved
   * the document a second time could act on whichever one the editor had moved on
   * to by then.
   */
  const addAnnotation = useCallback(
    async (docId: string, input: AnnotationInput): Promise<string | undefined> => {
      try {
        const { annotation } = await api.addAnnotation(docId, input)
        await refresh()
        return annotation.id
      } catch (error) {
        toast.error(refusalText(error))
        return undefined
      }
    },
    [refresh],
  )

  /**
   * Change an annotation before it goes (spec-00007-FR-3): its text, its type, or
   * its selection. What comes back is whether it went, for the same reason the
   * ask entry needs to know.
   */
  const changeAnnotation = useCallback(
    async (docId: string, annotationId: string, change: AnnotationChange): Promise<boolean> => {
      try {
        await api.changeAnnotation(docId, annotationId, change)
      } catch (error) {
        toast.error(refusalText(error))
        return false
      }
      await refresh()
      return true
    },
    [refresh],
  )

  const removeAnnotation = useCallback(
    (docId: string, annotationId: string) => {
      void run(() => api.removeAnnotation(docId, annotationId))
    },
    [run],
  )

  /**
   * Enter the re-anchor mode on one annotation, or leave it. Entering takes the
   * editor to the body state it was last on — editing, with no record — because
   * a selection is what the mode is waiting for (design-00002 §16.4).
   */
  const startReanchor = useCallback(
    (docId: string, annotationId?: string) => {
      setReanchoring(annotationId)
      if (annotationId === undefined) return
      showEditorMode(docId, bodyMode.current)
    },
    [showEditorMode],
  )

  /** The new selection accepted: the anchor and the quote are replaced, and the list comes back. */
  const finishReanchor = useCallback(
    async (docId: string, anchor: AnnotationInput['anchor']): Promise<boolean> => {
      const annotationId = reanchoring
      if (annotationId === undefined) return false
      if (!(await changeAnnotation(docId, annotationId, { anchor }))) return false
      setReanchoring(undefined)
      showEditorMode(docId, 'annotations')
      return true
    },
    [reanchoring, changeAnnotation, showEditorMode],
  )

  /**
   * Locate an annotation, or stop being located on one. The locate lands on the
   * body state the editor was last on — the one the reader was reading — and the
   * mark itself is the editor's and the preview's own (design-00002 §16.6).
   */
  const locateAnnotation = useCallback(
    (docId: string, annotationId?: string) => {
      setLocatedAnnotation(annotationId)
      // Counted, so the same annotation asked for twice is two asks: the id alone
      // would leave the second press with nothing to change and no way to bring a
      // cleared mark back (design-00002 §16.6).
      if (annotationId === undefined) return
      setLocatedAt((count) => count + 1)
      showEditorMode(docId, bodyMode.current)
    },
    [showEditorMode],
  )

  /**
   * The unified submit (spec-00007-FR-5). The two endings are read off the status
   * code, the line design-00001 §12.3 fixes: **4xx is a batch that did not happen
   * at all**, said in one toast with the list untouched; **200 is a batch that
   * ran**, said in one summary toast, with each held-back annotation's reason
   * left on its own row — a toast disappears and a held-back annotation has to be
   * dealt with.
   *
   * On the way out the terminal switches to the cowrite session (design-00002
   * §15) while the editor keeps whatever it was showing: spec-00006-FR-4's
   * one-off Source override does not apply to an annotation submit
   * (spec-00007-AC-8.6).
   */
  const submitAnnotations = useCallback(
    async (docId: string, agents: { question?: string; cowrite?: string }) => {
      setSubmitting(true)
      try {
        const result = await api.submitAnnotations(docId, { unsavedChanges: unsavedBuffer, agents })
        const sent = result.submitted.questions.length + (result.submitted.issues?.annotationIds.length ?? 0)
        const held = result.blocked.length
        if (held === 0) toast.message(`submitted ${sent} annotations`)
        else toast.error(`submitted ${sent}, held back ${held}`)
        // A transition whose commit failed is a notice, never a refusal: the file
        // is `draft` on disk and the session went ahead (spec-00007-AC-7.5).
        if (result.transition?.error !== undefined) toast.error(result.transition.error)
        for (const warning of result.warnings ?? []) toast.error(warning)
        const session = result.submitted.issues?.sessionId
        if (session !== undefined) {
          shownRef.current = session
          setShownId(session)
          setTerminalOpen(true)
        }
        await refresh()
      } catch (error) {
        const word = error instanceof ApiError ? error.reason : undefined
        toast.error((word === undefined ? undefined : SUBMIT_REFUSAL[word]) ?? refusalText(error))
      } finally {
        setSubmitting(false)
      }
    },
    [refresh, unsavedBuffer],
  )

  /**
   * The one way a session ends on the user's word (spec-00001-FR-49): the named
   * one, and no other, however many are running (spec-00003-FR-5, AC-5.3). The
   * session panel names the row's; the terminal's own stop names nothing and
   * means the one on show, and with none on show there is nothing to stop. The
   * board does not assume what the stop did — the refresh that follows re-reads
   * the listing, which is where the end state, the counts and the entries all
   * come from.
   */
  const stopSession = useCallback(
    async (named?: string) => {
      const id = named ?? shownRef.current
      if (id === undefined) return
      await run(() => api.stopSession(id))
    },
    [run],
  )

  const advance = useCallback(
    async (sourceId: string, targetType: string) => {
      await startSession(() => api.advance(sourceId, targetType, agent))
    },
    [startSession, agent],
  )

  /**
   * Open a document's own text, or close the panel. Either way the prefilled
   * buffer goes: a creation abandoned must not follow the next document into the
   * editor.
   */
  const edit = useCallback(
    (id?: string) => {
      setDraft(undefined)
      setEditing(id)
      // The view state, the location and the threads all belong to the document
      // that was open: another one opens on its own text (design-00002 §14). The
      // annotations, the located one and the re-anchor gesture go the same way.
      setEditorMode('source')
      forget(id)
      if (id !== undefined) void readAnnotations()
    },
    [forget, readAnnotations],
  )

  /**
   * Open a new document's buffer (spec-00001-FR-53). The server allocates the
   * number and hands back the type's template; the id is that prefix and the
   * slug the user chose. Nothing is written — the buffer is created on save, so
   * a dialog thought better of leaves no file behind.
   */
  const create = useCallback(async (type: string, slug: string) => {
    try {
      const { idPrefix, template } = await api.createPrefill(type)
      const id = `${idPrefix}${slug}`
      setDraft(prefillFrontMatter(template, id, type))
      setEditing(id)
      // A buffer that is not a document yet has no annotations, and nothing of the
      // last document's editor may follow it here.
      forget(undefined)
    } catch (error) {
      toast.error(refusalText(error))
    }
  }, [forget])

  /**
   * A created document exists from here on, so the prefilled buffer is done with
   * and the board takes the new node in and selects it: what was just created is
   * what the user wants in front of them (spec-00001-FR-53).
   */
  const created = useCallback(async () => {
    const id = editing
    setDraft(undefined)
    setEditing(undefined)
    const next = await refresh()
    if (id !== undefined && next.nodes.some((node) => node.id === id)) await load(id)
  }, [editing, refresh, load])

  // Only a spec or a rule declares requirement items, and only for those does
  // the inspector panel exist at all (spec-00001-FR-31). A document whose front
  // matter is broken still gets read: its body is what the panel is for.
  useEffect(() => {
    const node = graph.nodes.find((candidate) => candidate.id === selected)
    if (!node || !declaresItems(node.type)) {
      setItems(undefined)
      return
    }
    let live = true
    void api
      .items(node.id)
      .then((view) => {
        if (live) setItems({ docId: node.id, view })
      })
      .catch((error) => {
        if (!live) return
        setItems(undefined)
        toast.error(error instanceof Error ? error.message : String(error))
      })
    return () => {
      live = false
    }
  }, [graph, selected])

  // Which document the fifth read is for: the one in the editor, and only while
  // a cowrite session is running on it (spec-00006-FR-4). The moment there is
  // none — the session ended, another document was opened — the disk text goes
  // with it and the editor is back to its ordinary behaviour (AC-4.4).
  useEffect(() => {
    const held = sessions.some((one) => !ended(one) && one.kind === 'cowrite' && one.sourceId === editing)
    cowriting.current = held ? editing : undefined
    if (!held) setDisk(undefined)
  }, [sessions, editing])

  /**
   * The two agent lists, taken off an effective agent list (spec-00009-FR-3).
   * The board holds no config object — it holds these two arrays, which every
   * starting point reads — so this is the one place the list becomes them, for
   * the first read and for a save alike (design-00002 §18.4). `headless` is a
   * boolean from the twenty-sixth round on, so the ask's set is a truth test and
   * not a «declared» test (design-00001 §7).
   *
   * The agent on show is corrected as it goes: one the settings panel has just
   * removed would otherwise be named on the next request and refused, so a name
   * the new list does not carry falls back to the first — which is also the
   * reading «one agent is no choice at all» keeps (spec-00001-AC-55.4).
   */
  const applyAgents = useCallback((effective: EffectiveAgent[]) => {
    const names = effective.map((one) => one.name)
    setAgents(names)
    setAskAgents(effective.filter((one) => one.headless).map((one) => one.name))
    setAgent((current) =>
      names.length > 1 ? (current !== undefined && names.includes(current) ? current : names[0]) : undefined,
    )
  }, [])

  // The first read of everything. Sessions outlive the browser, so this read is
  // also where a board opening fresh finds the ones still running and reattaches
  // to one of them — `refresh` above holds that (spec-00003-FR-9).
  useEffect(() => {
    // Config first: laying out before the column order lands would place every
    // node in the unknown-type bucket and then move it (spec-00001-AC-1.12).
    void (async () => {
      try {
        const config = await api.config()
        typeOrder.current = Object.keys(config.types)
        setKinds(config.types)
        setRelationOrder(config.relations)
        setClarifiable(config.clarifiable)
        setAuditable(config.auditable)
        setEntry(config.entry)
        setMaxSessions(config.maxSessions)
        // An ask can only be put to an agent that says how to run it headlessly
        // (spec-00005-FR-8); the entries follow this set the way they follow the
        // clarifiable and auditable ones (spec-00001-FR-56).
        applyAgents(config.agents)
      } catch (error) {
        // A board with no column order still beats no board: the graph is the
        // thing the user came for, so draw it and say why it looks odd.
        toast.error(error instanceof Error ? error.message : String(error))
      }
      await refresh()
    })()
  }, [refresh, applyAgents])

  // docs/ moves under the board more often than the board moves it — an agent
  // or an editor elsewhere — so the change is pushed and the board re-reads
  // (spec-00001-FR-42). No channel means no push, which costs the board nothing
  // else (spec-00001-FR-43).
  useEffect(() => {
    const link = connectEvents(() => void refresh())
    return () => link.close()
  }, [refresh])

  return {
    graph,
    placed,
    kinds,
    relationOrder,
    clarifiable,
    auditable,
    entry,
    agents,
    askAgents,
    agent,
    selected,
    selectedNode: graph.nodes.find((node) => node.id === selected),
    items: items !== undefined && items.docId === selected ? items.view : undefined,
    transitions,
    nextSteps,
    editing,
    draft,
    // The cowrite target's text on disk, and only ever for the document the
    // editor is on (spec-00006-FR-4).
    disk: disk !== undefined && disk.docId === editing ? disk.content : undefined,
    // The editor's third view state and what it is located on, held here so a
    // panel row and a notification can set them (design-00002 §14).
    editorMode,
    threads,
    located,
    // The annotations of the document in the editor, and only ever for that one
    // (design-00002 §16.8). The fourth view state, its located annotation and the
    // re-anchor gesture are all held here, so the list — which lives beside the
    // editor rather than inside it — can drive them.
    annotations: annotations !== undefined && annotations.docId === editing ? annotations.view : undefined,
    locatedAnnotation,
    locatedAt,
    reanchoring,
    unsavedBuffer,
    submitting,
    terminalOpen,
    sessions,
    // Held by id, resolved from the current listing: a refresh keeps the user on
    // the same session, and one that is gone takes only its terminal view with
    // it (spec-00003-AC-5.6, design-00002 §10).
    shownSession: sessions.find((one) => one.id === shownId),
    // What the top bar counts and what the concurrency rules are read off: the
    // running sessions (spec-00003-FR-3, FR-4), and of those the ones waiting on
    // an answer (FR-6). A count of zero renders no badge, which is the caller's
    // reading of these numbers, not this hook's.
    running: runningOf(sessions),
    awaitingCount: sessions.filter((one) => !ended(one) && one.awaiting === true).length,
    maxSessions,
    // The desktop notification switch: the three-state reading it shows, and the
    // click that is the one place a permission is asked for (spec-00004-FR-1).
    notifyState: notifications.state,
    toggleNotify: notifications.toggle,
    coverageOpen,
    coverage,
    showCoverage,
    edit,
    setTerminalOpen,
    setAgent,
    // What a settings save hands back: the list it made effective, which this
    // page — and only this page — shows from then on (spec-00009-FR-8).
    applyAgents,
    refresh,
    select,
    deselect,
    run,
    startSession,
    showSession,
    showAsks,
    showEditorMode,
    locate: setLocated,
    locateAnnotation,
    addAnnotation,
    changeAnnotation,
    removeAnnotation,
    startReanchor,
    finishReanchor,
    submitAnnotations,
    setUnsavedBuffer,
    ask,
    cowrite,
    stopSession,
    advance,
    create,
    created,
  }
}

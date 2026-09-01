import { existsSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it, vi } from 'vitest'
import { type SelectionAnchor, anchorAt, normalizeText } from '../src/annotationAnchor.ts'
import { ANNOTATIONS_DIR } from '../src/annotationStore.ts'
import { Annotations } from '../src/annotations.ts'
import type { AgentConfig } from '../src/config.ts'
import { DocService } from '../src/docService.ts'
import { type CommitOutcome, GitLayer } from '../src/gitLayer.ts'
import { spawnPty, unrunnable } from '../src/pty.ts'
import { SessionBusyError, SessionManager, type SessionOutcome, type SpawnPty } from '../src/sessionManager.ts'
import { commitCount, cowriteConfig, doc, lastCommitMessage, makeRepo } from './helpers.ts'
import { WorkflowError } from '../src/workflow.ts'

const BODY = '# Spec\n\nThe gate is cheap to check.\n\nAnd another sentence entirely.\n\nA third paragraph.\n'
const SPEC = (status: string) => doc({ id: 'spec-00001-x', type: 'spec', status }, BODY)
const PLAN = (status: string) => doc({ id: 'plan-00001-y', type: 'plan', status }, '# Plan\n\nThe gate is cheap.\n')
const WONTFIX_ISSUE = doc({ id: 'issue-00001-z', type: 'issue', status: 'wontfix' }, '# Issue\n\nThe gate.\n')
const BROKEN = doc({ id: 'nope', type: 'spec', status: 'draft' }, '# Broken\n\nThe gate is cheap to check.\n')

const GATE = 'The gate is cheap to check.'
const OTHER = 'And another sentence entirely.'
const THIRD = 'A third paragraph.'

/** The one agent of the test config declares a headless form; this one declares none. */
const PLAIN: AgentConfig = { name: 'plain', command: 'node', args: [], cwd: 'docs' }

/** A second agent that does declare one, for the per-path choice of spec-00007-AC-5.6. */
const HEADLESS: AgentConfig = { ...cowriteConfig().agents[0]!, name: 'second' }

/** What a wrap-up hands back by default: a collapse that committed, and the hash it committed as. */
const COLLAPSED: SessionOutcome = { docId: 'spec-00001-x', problems: [], committed: true, sha: 'abc1234' }

interface BoardOptions {
  agents?: AgentConfig[]
  maxSessions?: number
  onExit?: () => Promise<SessionOutcome>
  git?: (repoRoot: string) => GitLayer
  /** The pty seam throwing: spec-00001-FR-16's asynchronous half (design-00001 §12.6). */
  spawnThrows?: boolean
  /** The ask receipt chain refusing for a reason of its own, rather than for a concurrency rule. */
  askRefusal?: Error
  /** Hold every ask call until the test releases it, so a submit can be observed mid-flight. */
  holdAsk?: boolean
}

/**
 * The annotation service on a real repo, with a real doc service and a real
 * registry behind it. Two things are stand-ins: the pty, so no process is
 * spawned, and the ask receipt chain — which is one function shared with the ask
 * entry and proved over that entry in server.test.ts. The stand-in still goes
 * through the registry's own admission, so the cap and the slot are the real ones.
 */
function boardOn(files: Record<string, string>, options: BoardOptions = {}) {
  const { repoRoot, docsDir } = makeRepo(files)
  const config = cowriteConfig()
  if (options.agents) config.agents = options.agents
  if (options.maxSessions !== undefined) config.maxSessions = options.maxSessions
  const docs = new DocService(repoRoot, docsDir, config, options.git?.(repoRoot))
  const spawned: Array<{ command: string; args: string[]; cwd: string }> = []
  const written: string[] = []
  const exits: Array<(exitCode: number) => void> = []
  const spawn: SpawnPty = (command, args, cwd) => {
    if (options.spawnThrows) throw new Error('the pty seam threw')
    spawned.push({ command, args, cwd })
    const listeners: Array<(event: { exitCode: number }) => void> = []
    let gone = false
    const end = (exitCode: number) => {
      if (gone) return
      gone = true
      for (const listener of listeners) listener({ exitCode })
    }
    exits.push(end)
    return {
      onData: () => {},
      onExit: (listener) => void listeners.push(listener),
      write: (data) => void written.push(data),
      resize: () => {},
      kill: () => end(0),
    }
  }
  const opened: Array<{ docId: string; question: string; agent: string; selection: SelectionAnchor }> = []
  let release = () => {}
  const held: Promise<void> = options.holdAsk
    ? new Promise<void>((resolve) => {
        release = resolve
      })
    : Promise.resolve()
  const sessions: SessionManager = new SessionManager({
    agents: config.agents,
    maxSessions: config.maxSessions,
    repoRoot,
    spawn,
    snapshot: () => docs.snapshotDocs(),
    contentSnapshot: () => docs.contentSnapshotDocs(),
    onSessionEnd: (info) => annotations.landBatch(info),
    onExit: options.onExit ?? (async () => COLLAPSED),
  })
  const annotations: Annotations = new Annotations({
    repoRoot,
    docs,
    sessions,
    agents: config.agents,
    openAsk: async (input) => {
      opened.push(input)
      await held
      if (options.askRefusal) throw options.askRefusal
      // An ask holds a slot and no document, which is what the cap counts
      // (spec-00005-FR-6): the refusals this path can meet are the registry's own.
      const info = sessions.start(
        { kind: 'ask', sourceId: input.docId, instruction: input.question, threadId: `t-${opened.length}` },
        input.agent,
      )
      return { sessionId: info.id, threadId: `t-${opened.length}` }
    },
  })
  docs.attachCowriteProbe((docId) => sessions.cowriteOn(docId))
  return {
    repoRoot,
    docsDir,
    docs,
    sessions,
    annotations,
    spawned,
    written,
    opened,
    /** End the nth terminal session the way a process ends. */
    exit: (index = 0, exitCode = 0) => exits[index]!(exitCode),
    /** Let the held ask calls through (`holdAsk`). */
    release: () => release(),
  }
}

type Board = ReturnType<typeof boardOn>

/** The anchor the editor would cut for that passage of that file. */
function anchorFor(docsDir: string, relPath: string, selected: string): SelectionAnchor {
  const text = normalizeText(readFileSync(join(docsDir, relPath), 'utf8'))
  const at = text.indexOf(selected)
  if (at === -1) throw new Error(`${selected} is not in ${relPath}`)
  return anchorAt(text, at, at + selected.length)
}

describe('adding an annotation', () => {
  // spec-00007-AC-1.1 over the service: the record and everything it carries
  it('records the type, the text, the anchor, the quote and the moment', async () => {
    const { docsDir, annotations } = boardOn({ 'spec/x.md': SPEC('active') })

    const added = await annotations.add('spec-00001-x', {
      type: 'question',
      text: 'why two gates?',
      anchor: anchorFor(docsDir, 'spec/x.md', GATE),
    })

    expect(added).toMatchObject({ id: 'n-1', type: 'question', text: 'why two gates?', state: 'pending' })
    expect(added.quote).toBe(GATE)
    expect(added.anchor.before).toContain('# Spec')
    expect(added.createdAt).toMatch(/^\d{4}-\d{2}-\d{2}T.+Z$/)
  })

  /**
   * spec-00007-AC-1.3 — the anchor may have been cut from a buffer nobody but the
   * editor holds: nothing is read off the disk to make an annotation, so text that
   * is not saved yet is annotated like any other.
   */
  // spec-00007-AC-1.3
  it('records an annotation on text that is not on disk at all', async () => {
    const { annotations } = boardOn({ 'spec/x.md': SPEC('active') })

    const added = await annotations.add('spec-00001-x', {
      type: 'issue',
      text: 'this new sentence needs a source',
      anchor: { before: 'unsaved ', selected: 'a sentence only the buffer holds', after: ' text' },
    })

    expect(added.quote).toBe('a sentence only the buffer holds')
  })

  // spec-00007-AC-1.4 — an annotation with nothing in it is not recorded
  it('refuses an empty text, and one that is only whitespace', async () => {
    const { docsDir, annotations } = boardOn({ 'spec/x.md': SPEC('draft') })
    const anchor = anchorFor(docsDir, 'spec/x.md', GATE)

    for (const text of ['', '   \n ']) {
      await expect(annotations.add('spec-00001-x', { type: 'question', text, anchor })).rejects.toMatchObject({
        reason: 'empty-text',
      })
    }
    expect(annotations.list('spec-00001-x').annotations).toEqual([])
  })

  // spec-00007-AC-4.2 — the type gate holds at the interface, not only in the menu
  it('refuses an issue on an archived document, and takes a question', async () => {
    const { docsDir, annotations } = boardOn({ 'spec/x.md': SPEC('archived') })
    const anchor = anchorFor(docsDir, 'spec/x.md', GATE)

    await expect(annotations.add('spec-00001-x', { type: 'issue', text: 'change this', anchor })).rejects.toMatchObject({
      reason: 'type-ineligible',
    })
    await expect(annotations.add('spec-00001-x', { type: 'question', text: 'why?', anchor })).resolves.toMatchObject({
      id: 'n-1',
    })
  })

  // spec-00007-AC-4.6 — an anomalous document takes no annotation of either type
  it('refuses either type on a document whose front matter will not read', async () => {
    const { annotations } = boardOn({ 'spec/broken.md': BROKEN })
    const anchor: SelectionAnchor = { before: '', selected: GATE, after: '' }

    for (const type of ['question', 'issue'] as const) {
      await expect(annotations.add('nope', { type, text: 'x', anchor })).rejects.toMatchObject({
        reason: 'doc-anomalous',
      })
    }
  })

  /**
   * spec-00007-AC-10.5 — the mirror of the issue gate: with no agent declaring a
   * headless form nothing could answer a question, so that type is refused while
   * the issue type is untouched.
   */
  // spec-00007-AC-10.5
  it('refuses a question when no agent declares a headless form, and takes an issue', async () => {
    const { docsDir, annotations } = boardOn({ 'spec/x.md': SPEC('draft') }, { agents: [PLAIN] })
    const anchor = anchorFor(docsDir, 'spec/x.md', GATE)

    await expect(annotations.add('spec-00001-x', { type: 'question', text: 'why?', anchor })).rejects.toMatchObject({
      reason: 'type-ineligible',
    })
    await expect(annotations.add('spec-00001-x', { type: 'issue', text: 'change', anchor })).resolves.toMatchObject({
      id: 'n-1',
    })
  })

  it('refuses a body that is no annotation at all', async () => {
    const { docsDir, annotations } = boardOn({ 'spec/x.md': SPEC('draft') })
    const anchor = anchorFor(docsDir, 'spec/x.md', GATE)

    for (const body of [
      undefined,
      {},
      { type: 'comment', text: 'x', anchor },
      { type: 'question', text: 3, anchor },
      { type: 'question', text: 'x', anchor: { selected: 'a', before: 'b' } },
      { type: 'question', text: 'x', anchor: { selected: '', before: '', after: '' } },
    ]) {
      await expect(annotations.add('spec-00001-x', body)).rejects.toThrow()
    }
  })
})

describe('changing an annotation', () => {
  async function withQuestion(status = 'draft') {
    const board = boardOn({ 'spec/x.md': SPEC(status) })
    await board.annotations.add('spec-00001-x', {
      type: 'question',
      text: 'why two gates?',
      anchor: anchorFor(board.docsDir, 'spec/x.md', GATE),
    })
    return board
  }

  // spec-00007-FR-3 — the text, the type and the selection are all changeable
  it('changes the text and re-anchors to another passage', async () => {
    const board = await withQuestion()

    await board.annotations.change('spec-00001-x', 'n-1', { text: 'why two gates, really?' })
    const moved = await board.annotations.change('spec-00001-x', 'n-1', {
      anchor: anchorFor(board.docsDir, 'spec/x.md', OTHER),
    })

    expect(moved).toMatchObject({ text: 'why two gates, really?', quote: OTHER })
    expect(board.annotations.list('spec-00001-x').annotations[0]!.locate).toMatchObject({ start: expect.any(Number) })
  })

  // The type gate holds on a change as it holds on an add (spec-00007-FR-3)
  it('refuses a change to issue on a document no cowrite may be started on', async () => {
    const board = await withQuestion('archived')

    await expect(board.annotations.change('spec-00001-x', 'n-1', { type: 'issue' })).rejects.toMatchObject({
      reason: 'type-ineligible',
    })
  })

  it('refuses a change that empties the text', async () => {
    const board = await withQuestion()

    await expect(board.annotations.change('spec-00001-x', 'n-1', { text: ' ' })).rejects.toMatchObject({
      reason: 'empty-text',
    })
  })

  it('refuses a change whose fields are not what they have to be', async () => {
    const board = await withQuestion()

    await expect(board.annotations.change('spec-00001-x', 'n-1', { text: 7 })).rejects.toThrow()
    await expect(board.annotations.change('spec-00001-x', 'n-1', { anchor: 'here' })).rejects.toThrow()
    await expect(board.annotations.change('spec-00001-x', 'n-1', undefined)).resolves.toMatchObject({ id: 'n-1' })
  })

  /**
   * spec-00007-FR-3 with FR-11 — the annotations outlive the document, so the
   * change entry is as tolerant of a document that has gone as the delete and the
   * list entries are. Only what actually needs the document asks for it: a type,
   * whose eligibility is a reading of the status, and an anchor, which is a
   * selection of a body.
   */
  it('changes the text of an annotation whose document has been deleted, and refuses to retype it', async () => {
    const board = await withQuestion()
    rmSync(join(board.docsDir, 'spec/x.md'))
    board.docs.invalidate()

    await expect(board.annotations.change('spec-00001-x', 'n-1', { text: 'still worth asking' })).resolves.toMatchObject(
      { text: 'still worth asking' },
    )
    await expect(board.annotations.change('spec-00001-x', 'n-1', { type: 'issue' })).rejects.toMatchObject({
      reason: 'doc-missing',
    })
  })

  it('drops an annotation of a document that is no longer there at all', async () => {
    const board = await withQuestion()
    rmSync(join(board.docsDir, 'spec/x.md'))
    board.docs.invalidate()

    await board.annotations.remove('spec-00001-x', 'n-1')

    expect(board.annotations.list('spec-00001-x').annotations).toEqual([])
  })
})

/**
 * The statement the submit entry is drawn from (spec-00007-FR-5, FR-4, FR-10):
 * the server is the one place the type sets and the transition are decided.
 */
describe('the submit preview', () => {
  const previewOf = (files: Record<string, string>, docId: string, agents?: AgentConfig[]) =>
    boardOn(files, agents ? { agents } : {}).annotations.list(docId).submitPreview

  // spec-00007-AC-4.3 — a draft offers both
  it('offers both types on a draft document', () => {
    expect(previewOf({ 'spec/x.md': SPEC('draft') }, 'spec-00001-x')).toMatchObject({
      issueEligible: true,
      questionEligible: true,
      willTransitionTo: null,
    })
  })

  // spec-00007-AC-4.4 — an open work item is cowritable outright, so no transition
  it('offers both types on an open plan, with no transition to make', () => {
    expect(previewOf({ 'plan/y.md': PLAN('open') }, 'plan-00001-y')).toMatchObject({
      issueEligible: true,
      questionEligible: true,
      willTransitionTo: null,
    })
  })

  // spec-00007-AC-4.1 — a resolved plan: the issue type is out, the question in
  it('withholds the issue type on a resolved plan', () => {
    expect(previewOf({ 'plan/y.md': PLAN('resolved') }, 'plan-00001-y')).toMatchObject({
      issueEligible: false,
      questionEligible: true,
    })
  })

  // spec-00007-AC-4.5 — and on a work item closed as wontfix
  it('withholds the issue type on a wontfix issue document', () => {
    expect(previewOf({ 'issue/z.md': WONTFIX_ISSUE }, 'issue-00001-z')).toMatchObject({
      issueEligible: false,
      questionEligible: true,
    })
  })

  it('withholds the issue type on an archived document', () => {
    expect(previewOf({ 'spec/x.md': SPEC('archived') }, 'spec-00001-x').issueEligible).toBe(false)
  })

  // spec-00007-AC-4.6 — an anomalous document offers neither
  it('withholds both types on an anomalous document', () => {
    expect(previewOf({ 'spec/broken.md': BROKEN }, 'nope')).toMatchObject({
      issueEligible: false,
      questionEligible: false,
    })
  })

  // spec-00007-AC-10.5 — the configuration gate, server-side
  it('withholds the question type when no agent declares a headless form', () => {
    expect(previewOf({ 'spec/x.md': SPEC('draft') }, 'spec-00001-x', [PLAIN])).toMatchObject({
      issueEligible: true,
      questionEligible: false,
    })
  })

  /**
   * spec-00007-AC-5.7 — what this submit will do: the counts by type off the
   * unsubmitted region, and the transition an `active` living document needs
   * before it can be cowritten.
   */
  // spec-00007-AC-5.7
  it('states one ask, a cowrite and the transition for a question and two issues', async () => {
    const { docsDir, annotations } = boardOn({ 'spec/x.md': SPEC('active') })
    const anchor = anchorFor(docsDir, 'spec/x.md', GATE)
    await annotations.add('spec-00001-x', { type: 'question', text: 'why?', anchor })
    await annotations.add('spec-00001-x', { type: 'issue', text: 'name the gate', anchor })
    await annotations.add('spec-00001-x', { type: 'issue', text: 'and the other', anchor })

    expect(annotations.list('spec-00001-x').submitPreview).toEqual({
      questions: 1,
      issues: 2,
      willTransitionTo: 'draft',
      issueEligible: true,
      questionEligible: true,
    })
  })

  // With nothing to cowrite there is no revision round either: what is stated is
  // what this submit would do.
  it('states no transition on an active document with only a question to submit', async () => {
    const { docsDir, annotations } = boardOn({ 'spec/x.md': SPEC('active') })
    await annotations.add('spec-00001-x', {
      type: 'question',
      text: 'why?',
      anchor: anchorFor(docsDir, 'spec/x.md', GATE),
    })

    expect(annotations.list('spec-00001-x').submitPreview).toMatchObject({ questions: 1, willTransitionTo: null })
  })

  /**
   * The counts do not discount orphans: an anchor is read at the moment of the
   * submit and nowhere else, so a preview that read them would move a whole-file
   * scan into every refresh and still disagree with what the submit finds.
   */
  it('counts an annotation whose anchor no longer lands', async () => {
    const { docsDir, annotations } = boardOn({ 'spec/x.md': SPEC('draft') })
    await annotations.add('spec-00001-x', {
      type: 'question',
      text: 'why?',
      anchor: anchorFor(docsDir, 'spec/x.md', GATE),
    })
    writeFileSync(join(docsDir, 'spec/x.md'), SPEC('draft').replace(GATE, 'Rewritten entirely.'))

    const view = annotations.list('spec-00001-x')
    expect(view.submitPreview.questions).toBe(1)
    expect(view.annotations[0]!.locate).toEqual({ failed: 'missing' })
  })

  /**
   * spec-00007-AC-9.9 and AC-5.3 — nothing annotated yet: an empty list, the gates
   * as they stand, and an empty submit refused.
   */
  // spec-00007-AC-9.9
  // spec-00007-AC-5.3
  it('answers an empty list for a document nobody has annotated, and refuses an empty submit', async () => {
    const { annotations } = boardOn({ 'spec/x.md': SPEC('draft') })

    expect(annotations.list('spec-00001-x')).toEqual({
      annotations: [],
      batches: [],
      submitPreview: {
        questions: 0,
        issues: 0,
        willTransitionTo: null,
        issueEligible: true,
        questionEligible: true,
      },
    })
    await expect(annotations.submit('spec-00001-x', {})).rejects.toMatchObject({ reason: 'empty-submit' })
  })
})

/**
 * spec-00007-FR-12 — a submitted annotation whose anchor stops landing is not a
 * failed annotation: the state does not move, the quote stays readable, and only
 * the locating degrades.
 */
describe('an anchor that stops landing after the submit', () => {
  // spec-00007-AC-12.2
  it('leaves the state and the quote alone and reports the reading as failed', async () => {
    const { docsDir, annotations } = boardOn({ 'spec/x.md': SPEC('draft') })
    await annotations.add('spec-00001-x', {
      type: 'question',
      text: 'why?',
      anchor: anchorFor(docsDir, 'spec/x.md', GATE),
    })
    await annotations.submit('spec-00001-x', {})
    expect(annotations.list('spec-00001-x').annotations[0]).toMatchObject({ state: 'submitted', threadId: 't-1' })

    writeFileSync(join(docsDir, 'spec/x.md'), SPEC('draft').replace(GATE, 'The gate is dear to check.'))

    const [listed] = annotations.list('spec-00001-x').annotations
    expect(listed).toMatchObject({ state: 'submitted', threadId: 't-1', quote: GATE })
    expect(listed!.locate).toEqual({ failed: 'missing' })
  })

  /**
   * spec-00007-AC-12.1 — two issues in one batch and the session's revision of the
   * one rewrote the other's passage: that one's reading degrades and nothing else
   * about either of them moves.
   */
  // spec-00007-AC-12.1
  // spec-00007-AC-12.3
  it('degrades one annotation’s reading and not its neighbour’s', async () => {
    const { docsDir, annotations } = boardOn({ 'spec/x.md': SPEC('draft') })
    for (const [text, selected] of [
      ['name the gate', GATE],
      ['and this one', OTHER],
    ]) {
      await annotations.add('spec-00001-x', {
        type: 'issue',
        text: text!,
        anchor: anchorFor(docsDir, 'spec/x.md', selected!),
      })
    }
    await annotations.submit('spec-00001-x', {})
    writeFileSync(join(docsDir, 'spec/x.md'), SPEC('draft').replace(OTHER, 'Rewritten by the session.'))

    const listed = annotations.list('spec-00001-x').annotations
    expect(listed[0]!.locate).toMatchObject({ start: expect.any(Number) })
    expect(listed[1]!.locate).toEqual({ failed: 'missing' })
    expect(listed.every((annotation) => annotation.state === 'submitted')).toBe(true)
    expect(listed[1]!.quote).toBe(OTHER)
  })
})

/**
 * The list read holds its own against a document the parsed tree still remembers
 * and the disk no longer has: nothing to locate against, and everything else
 * served as it stands.
 */
describe('a document the tree still remembers and the disk has lost', () => {
  it('serves the annotations with no reading of their anchors', async () => {
    const { docsDir, annotations } = boardOn({ 'spec/x.md': SPEC('draft') })
    await annotations.add('spec-00001-x', {
      type: 'question',
      text: 'why?',
      anchor: anchorFor(docsDir, 'spec/x.md', GATE),
    })

    // No invalidation: the node is still in the tree the board parsed.
    rmSync(join(docsDir, 'spec/x.md'))

    const view = annotations.list('spec-00001-x')
    expect(view.annotations[0]!.locate).toEqual({ failed: 'missing' })
    expect(view.annotations[0]!.quote).toBe(GATE)
  })
})

/**
 * spec-00007-FR-11 — the annotations of a document that has been deleted or
 * renamed are kept and simply have nowhere to be shown: the board answers, the
 * file stays, and neither type is offered on a document that is not there.
 */
describe('a document that is deleted', () => {
  // spec-00007-AC-11.3
  // spec-00007-AC-11.4
  it('keeps the annotations on disk and offers neither type', async () => {
    const { repoRoot, docsDir, docs, annotations } = boardOn({ 'spec/x.md': SPEC('draft') })
    await annotations.add('spec-00001-x', {
      type: 'question',
      text: 'why?',
      anchor: anchorFor(docsDir, 'spec/x.md', GATE),
    })

    rmSync(join(docsDir, 'spec/x.md'))
    docs.invalidate()

    const view = annotations.list('spec-00001-x')
    expect(view.annotations).toHaveLength(1)
    expect(view.annotations[0]!.locate).toEqual({ failed: 'missing' })
    expect(view.submitPreview).toMatchObject({ issueEligible: false, questionEligible: false })
    expect(existsSync(join(repoRoot, ANNOTATIONS_DIR, 'spec-00001-x.json'))).toBe(true)
  })
})

describe('a unified submit', () => {
  /** A document with annotations already on it: `[type, the passage]` each. */
  async function withAnnotations(
    status: string,
    marks: Array<['question' | 'issue', string]>,
    options?: BoardOptions,
  ) {
    const board = boardOn({ 'spec/x.md': SPEC(status) }, options)
    for (const [type, selected] of marks) {
      await board.annotations.add('spec-00001-x', {
        type,
        text: `${type}: ${selected}`,
        anchor: anchorFor(board.docsDir, 'spec/x.md', selected),
      })
    }
    return board
  }

  const pendingOf = (board: Board) =>
    board.annotations.list('spec-00001-x').annotations.filter((annotation) => annotation.state === 'pending')

  /**
   * spec-00007-AC-5.1 — the ordinary case: two questions and an issue, every
   * anchor landing, and nothing left in the unsubmitted region.
   */
  // spec-00007-AC-5.1
  // spec-00007-AC-7.3
  it('submits both types and leaves the unsubmitted region empty', async () => {
    const board = await withAnnotations('draft', [
      ['question', GATE],
      ['question', OTHER],
      ['issue', THIRD],
    ])

    const result = await board.annotations.submit('spec-00001-x', {})

    expect(result.submitted.questions.map((question) => question.annotationId)).toEqual(['n-1', 'n-2'])
    expect(result.submitted.issues).toMatchObject({ batchId: 'b-1', annotationIds: ['n-3'] })
    expect(result.blocked).toEqual([])
    expect(pendingOf(board)).toEqual([])
    expect(board.spawned).toHaveLength(1)
    expect(board.opened).toHaveLength(2)
  })

  /**
   * spec-00007-AC-5.2 — one anchor that no longer lands is held back on its own,
   * marked, and the others go: one failed reading does not stop the batch.
   */
  // spec-00007-AC-5.2
  it('holds back only the annotation whose passage was rewritten', async () => {
    const board = await withAnnotations('draft', [
      ['question', GATE],
      ['question', OTHER],
      ['issue', THIRD],
    ])
    writeFileSync(join(board.docsDir, 'spec/x.md'), SPEC('draft').replace(OTHER, 'Rewritten before the submit.'))

    const result = await board.annotations.submit('spec-00001-x', {})

    expect(result.blocked).toEqual([
      { annotationId: 'n-2', reason: 'orphan-missing', message: expect.stringContaining('no longer in the document') },
    ])
    expect(result.submitted.questions).toHaveLength(1)
    expect(result.submitted.issues).not.toBeNull()
    const [held] = pendingOf(board)
    expect(held).toMatchObject({ id: 'n-2', state: 'pending', orphan: 'missing', blocked: 'orphan-missing' })
    expect(held!.quote).toBe(OTHER)
  })

  /**
   * spec-00007-AC-2.3 — the passage now stands in two places: held back, never one
   * of them silently taken, and the two failures are told apart.
   */
  // spec-00007-AC-2.3
  it('holds back an annotation whose passage now stands in two places', async () => {
    const board = await withAnnotations('draft', [['issue', THIRD]])
    writeFileSync(join(board.docsDir, 'spec/x.md'), `${SPEC('draft')}\n${BODY}`)

    const result = await board.annotations.submit('spec-00001-x', {})

    expect(result.blocked[0]).toMatchObject({ annotationId: 'n-1', reason: 'orphan-ambiguous' })
    expect(result.submitted.issues).toBeNull()
    expect(board.spawned).toEqual([])
  })

  /**
   * spec-00007-AC-5.4 — the unsaved buffer is the front end's declaration and the
   * whole submit is refused on it: no thread, no session, nothing written.
   */
  // spec-00007-AC-5.4
  it('refuses the whole submit when the buffer is unsaved, starting nothing', async () => {
    const board = await withAnnotations('active', [
      ['question', GATE],
      ['issue', OTHER],
    ])

    await expect(board.annotations.submit('spec-00001-x', { unsavedChanges: true })).rejects.toMatchObject({
      reason: 'unsaved-buffer',
    })
    expect(board.spawned).toEqual([])
    expect(board.opened).toEqual([])
    expect(pendingOf(board)).toHaveLength(2)
    expect(readFileSync(join(board.docsDir, 'spec/x.md'), 'utf8')).toContain('status: active')
  })

  /**
   * spec-00007-AC-7.1 — an `active` document: the transition first, in its own
   * commit, and then one cowrite session whose instruction carries the skeleton,
   * every issue's passage and text, and the discipline clauses.
   */
  // spec-00007-AC-7.1
  it('moves an active document to draft in its own commit, then starts one cowrite', async () => {
    const board = await withAnnotations('active', [
      ['issue', GATE],
      ['issue', OTHER],
    ])
    const before = commitCount(board.repoRoot)

    const result = await board.annotations.submit('spec-00001-x', {})

    expect(result.transition).toEqual({ to: 'draft', committed: true })
    expect(lastCommitMessage(board.repoRoot)).toBe('wb(status): spec-00001-x')
    expect(commitCount(board.repoRoot)).toBe(before + 1)
    expect(readFileSync(join(board.docsDir, 'spec/x.md'), 'utf8')).toContain('status: draft')
    expect(board.spawned).toHaveLength(1)
    const instruction = board.written[0]!
    expect(instruction).toContain('spec/x.md')
    expect(instruction).toContain('spec/README.md')
    expect(instruction).toContain('never its front matter id or status line')
    expect(instruction).toContain('Issue 1 of 2 — the passage the owner marked in spec/x.md:')
    expect(instruction).toContain('Issue 2 of 2 — the passage the owner marked in spec/x.md:')
    expect(instruction).toContain(`[[${GATE}]]`)
    expect(instruction).toContain(`[[${OTHER}]]`)
    expect(instruction).toContain(`What they want changed: issue: ${GATE}`)
    expect(instruction).toContain('Work through the issues above one by one, in the order given')
    expect(instruction).toContain('stop and ask the owner — never guess')
    expect(instruction).toContain('report that implication and leave it to the')
    expect(instruction).toContain('Do no review action')
    expect(instruction.endsWith('Change nothing outside the docs tree.')).toBe(true)
  })

  /**
   * design-00001 §12.4 第 5 步 — the one interface promise this path makes to the
   * cowrite round: the session's baseline is read **after** the transition. Read
   * before it, the collapse would put `active` back (against spec-00007-AC-8.5)
   * and the editor guard would refuse every body save of the session.
   */
  // spec-00007-AC-8.5
  it('admits the session on the status the transition left, not the one it started from', async () => {
    const board = await withAnnotations('active', [['issue', GATE]])

    await board.annotations.submit('spec-00001-x', {})

    expect(board.sessions.cowriteOn('spec-00001-x')).toMatchObject({ preId: 'spec-00001-x', preStatus: 'draft' })
  })

  // spec-00007-AC-7.2 — a draft needs no transition, and makes no commit for one
  it('starts the cowrite straight away on a draft, with no transition commit', async () => {
    const board = await withAnnotations('draft', [['issue', GATE]])
    const before = commitCount(board.repoRoot)

    const result = await board.annotations.submit('spec-00001-x', {})

    expect(result.transition).toBeNull()
    expect(commitCount(board.repoRoot)).toBe(before)
    expect(board.spawned).toHaveLength(1)
  })

  /**
   * spec-00007-AC-7.5 — the transition's write landed and its commit did not: the
   * file is `draft` on disk, so the session goes ahead and the error rides back
   * (spec-00001-FR-20).
   */
  // spec-00007-AC-7.5
  it('starts the session when the transition wrote but could not commit', async () => {
    class NoCommits extends GitLayer {
      override async commit(): Promise<CommitOutcome> {
        return { committed: false, error: 'the index is locked' }
      }
    }
    const board = await withAnnotations('active', [['issue', GATE]], {
      git: (repoRoot) => new NoCommits(repoRoot),
    })

    const result = await board.annotations.submit('spec-00001-x', {})

    expect(result.transition).toEqual({ to: 'draft', committed: false, error: 'the index is locked' })
    expect(readFileSync(join(board.docsDir, 'spec/x.md'), 'utf8')).toContain('status: draft')
    expect(result.submitted.issues).not.toBeNull()
    expect(board.spawned).toHaveLength(1)
  })

  /**
   * spec-00007-AC-7.4 — the agent's command cannot be run at all: the precheck
   * catches it **before** the transition, so the document is still `active`, there
   * is no commit, and the issues stay where they are.
   */
  // spec-00007-AC-7.4
  it('leaves an active document untouched when the agent could not be started', async () => {
    const board = await withAnnotations('active', [['issue', GATE]], {
      agents: [{ name: 'gone', command: 'whiteboard-no-such-command', args: [], cwd: 'docs' }],
    })
    const before = commitCount(board.repoRoot)

    const result = await board.annotations.submit('spec-00001-x', {})

    expect(result.blocked).toEqual([
      { annotationId: 'n-1', reason: 'start-failed', message: expect.stringContaining('not found on PATH') },
    ])
    expect(result.transition).toBeNull()
    expect(result.submitted.issues).toBeNull()
    expect(readFileSync(join(board.docsDir, 'spec/x.md'), 'utf8')).toContain('status: active')
    expect(commitCount(board.repoRoot)).toBe(before)
    expect(board.spawned).toEqual([])
    expect(pendingOf(board)[0]).toMatchObject({ id: 'n-1', blocked: 'start-failed' })
  })

  /**
   * spec-00007-AC-4.7 — the gate is read again at the submit: the annotations were
   * made while the plan was `open`, and by now it is `resolved`. The issues are
   * held back whole and named for it; the question goes.
   */
  // spec-00007-AC-4.7
  it('holds back every issue when the document lost its eligibility, and submits the question', async () => {
    const board = boardOn({ 'plan/y.md': PLAN('open') })
    const anchor = anchorFor(board.docsDir, 'plan/y.md', 'The gate is cheap.')
    await board.annotations.add('plan-00001-y', { type: 'issue', text: 'name the gate', anchor })
    await board.annotations.add('plan-00001-y', { type: 'question', text: 'which gate?', anchor })
    writeFileSync(join(board.docsDir, 'plan/y.md'), PLAN('resolved'))

    const result = await board.annotations.submit('plan-00001-y', {})

    expect(result.blocked).toEqual([
      { annotationId: 'n-1', reason: 'gate-ineligible', message: expect.stringContaining('rule-00001-BR-29') },
    ])
    expect(result.submitted.questions).toHaveLength(1)
    expect(result.submitted.issues).toBeNull()
    expect(board.spawned).toEqual([])
  })

  /**
   * spec-00007-AC-10.1 and AC-10.2 — the document already has a session of its
   * own, this document's own earlier batch included: the issues are held back for
   * the exclusion, the question goes all the same, and the very same issue is
   * taken on the next submit once the session has ended.
   */
  // spec-00007-AC-10.1
  // spec-00007-AC-10.2
  it('holds back the issues while a session of its own runs, and takes them after it ends', async () => {
    const board = await withAnnotations('draft', [
      ['issue', GATE],
      ['question', OTHER],
    ])
    await board.annotations.submit('spec-00001-x', {})
    await board.annotations.add('spec-00001-x', {
      type: 'issue',
      text: 'one more passage',
      anchor: anchorFor(board.docsDir, 'spec/x.md', THIRD),
    })

    const held = await board.annotations.submit('spec-00001-x', {})

    expect(held.blocked).toEqual([
      { annotationId: 'n-3', reason: 'doc-busy', message: expect.stringContaining('already has a running') },
    ])
    expect(board.spawned).toHaveLength(1)

    board.exit(0)
    await board.sessions.whenFinished(board.sessions.list().find((session) => session.kind === 'cowrite')!.id)
    const again = await board.annotations.submit('spec-00001-x', {})

    expect(again.submitted.issues).toMatchObject({ annotationIds: ['n-3'] })
    expect(board.spawned).toHaveLength(2)
  })

  /**
   * spec-00007-AC-10.3 — the cap is reached: the issues are held back, and
   * **nothing** moved — no transition and no session.
   */
  // spec-00007-AC-10.3
  it('holds back the issues on a full cap without transitioning or starting anything', async () => {
    const board = await withAnnotations('active', [['issue', GATE]], { maxSessions: 1 })
    board.sessions.start({ kind: 'audit', sourceId: 'plan-00001-y', instruction: 'hold the one slot' })
    const before = commitCount(board.repoRoot)

    const result = await board.annotations.submit('spec-00001-x', {})

    expect(result.blocked).toEqual([
      { annotationId: 'n-1', reason: 'cap-reached', message: expect.stringContaining('max_sessions') },
    ])
    expect(result.transition).toBeNull()
    expect(commitCount(board.repoRoot)).toBe(before)
    expect(readFileSync(join(board.docsDir, 'spec/x.md'), 'utf8')).toContain('status: active')
    expect(board.spawned).toHaveLength(1)
  })

  /**
   * The compound corner spec-00007-FR-7 names and design-00001 §12.4 (a) sets out:
   * the last slot was taken **between** the judgment and the slot — the
   * transition's write and commit sit in that window. Nothing is rolled back: the
   * file stays `draft`, the transition is reported as it happened, and the issues
   * are held back to be submitted again down the no-transition branch.
   */
  it('reports the transition and holds the issues back when the slot went in the meantime', async () => {
    let takeTheSlot = () => {}
    class TakesTheSlot extends GitLayer {
      override async commit(paths: string[], message: string): Promise<CommitOutcome> {
        takeTheSlot()
        return super.commit(paths, message)
      }
    }
    const board = await withAnnotations('active', [['issue', GATE]], {
      maxSessions: 1,
      git: (repoRoot) => new TakesTheSlot(repoRoot),
    })
    takeTheSlot = () => {
      board.sessions.start({ kind: 'audit', sourceId: 'plan-00001-y', instruction: 'take the one slot' })
    }

    const result = await board.annotations.submit('spec-00001-x', {})

    expect(result.transition).toEqual({ to: 'draft', committed: true })
    expect(result.blocked).toEqual([
      { annotationId: 'n-1', reason: 'cap-reached', message: expect.stringContaining('max_sessions') },
    ])
    expect(result.submitted.issues).toBeNull()
    expect(readFileSync(join(board.docsDir, 'spec/x.md'), 'utf8')).toContain('status: draft')
    expect(board.sessions.list().map((session) => session.kind)).toEqual(['audit'])
    expect(pendingOf(board)[0]).toMatchObject({ id: 'n-1', blocked: 'cap-reached' })
  })

  /**
   * spec-00007-AC-5.8 — one slot left and a mixed batch: the cowrite is dispatched
   * first and takes it, and both questions are held back for the cap. The order is
   * the whole mechanism; no reservation is needed.
   */
  // spec-00007-AC-5.8
  it('gives the last slot to the cowrite and holds back the questions for the cap', async () => {
    const board = await withAnnotations(
      'draft',
      [
        ['question', GATE],
        ['question', OTHER],
        ['issue', THIRD],
      ],
      { maxSessions: 1 },
    )

    const result = await board.annotations.submit('spec-00001-x', {})

    expect(result.submitted.issues).toMatchObject({ annotationIds: ['n-3'] })
    expect(result.submitted.questions).toEqual([])
    expect(result.blocked.map((entry) => entry.reason)).toEqual(['cap-reached', 'cap-reached'])
    expect(pendingOf(board).map((annotation) => annotation.blocked)).toEqual(['cap-reached', 'cap-reached'])
  })

  // spec-00007-AC-6.3 — the cap holds one question back and the other goes
  it('holds back a single question the cap refuses and submits the rest', async () => {
    const board = await withAnnotations(
      'draft',
      [
        ['question', GATE],
        ['question', OTHER],
      ],
      { maxSessions: 1 },
    )

    const result = await board.annotations.submit('spec-00001-x', {})

    expect(result.submitted.questions).toHaveLength(1)
    expect(result.blocked).toEqual([
      { annotationId: 'n-2', reason: 'cap-reached', message: expect.stringContaining('max_sessions') },
    ])
  })

  /**
   * spec-00007-AC-6.1 and AC-6.2 — one first call and one thread per question,
   * each carrying its own marked passage and its own text.
   */
  // spec-00007-AC-6.1
  // spec-00007-AC-6.2
  it('opens one thread per question, each with its own passage', async () => {
    const board = await withAnnotations('draft', [
      ['question', GATE],
      ['question', OTHER],
    ])

    const result = await board.annotations.submit('spec-00001-x', {})

    expect(board.opened.map((call) => call.selection.selected)).toEqual([GATE, OTHER])
    expect(board.opened.map((call) => call.question)).toEqual([`question: ${GATE}`, `question: ${OTHER}`])
    expect(board.opened.map((call) => call.docId)).toEqual(['spec-00001-x', 'spec-00001-x'])
    expect(result.submitted.questions).toEqual([
      { annotationId: 'n-1', threadId: 't-1', sessionId: expect.any(String) },
      { annotationId: 'n-2', threadId: 't-2', sessionId: expect.any(String) },
    ])
    expect(board.annotations.list('spec-00001-x').annotations.map((one) => one.threadId)).toEqual(['t-1', 't-2'])
  })

  /**
   * spec-00007-AC-10.5 — nothing can answer a question: every one of them is held
   * back one by one, and the issues are dispatched all the same.
   */
  // spec-00007-AC-10.5
  it('holds back every question when nothing can answer one, and starts the cowrite', async () => {
    const board = await withAnnotations('draft', [['issue', OTHER]], { agents: [PLAIN] })
    // Recorded while some agent still declared a headless form; the configuration
    // lost it since, which is what this refusal is for.
    await board.annotations.store.add('spec-00001-x', {
      type: 'question',
      text: 'why?',
      anchor: anchorFor(board.docsDir, 'spec/x.md', GATE),
    })

    const result = await board.annotations.submit('spec-00001-x', {})

    expect(result.blocked).toEqual([
      { annotationId: 'n-2', reason: 'no-headless-agent', message: expect.stringContaining('headless form') },
    ])
    expect(result.submitted.issues).toMatchObject({ annotationIds: ['n-1'] })
    expect(board.opened).toEqual([])
  })

  /**
   * A question the receipt chain refused for a reason that is neither of the two
   * concurrency rules is held back all the same, and named as a start that failed
   * (design-00001 §12.3's seven reasons and no eighth).
   */
  it('holds back a question the ask chain refused for a reason of its own', async () => {
    const board = await withAnnotations('draft', [['question', GATE]], {
      askRefusal: new Error('the ask list of spec-00001-x cannot be read'),
    })

    const result = await board.annotations.submit('spec-00001-x', {})

    expect(result.blocked).toEqual([
      { annotationId: 'n-1', reason: 'start-failed', message: 'the ask list of spec-00001-x cannot be read' },
    ])
    expect(result.submitted.questions).toEqual([])
  })

  /**
   * spec-00007-AC-5.5 and AC-5.6 — each path defaults to the first agent of **its
   * own** set: the cowrite to the first configured, the question to the first that
   * declares a headless form.
   */
  // spec-00007-AC-5.5
  // spec-00007-AC-5.6
  it('defaults each path to the first agent of its own set', async () => {
    const board = await withAnnotations(
      'draft',
      [
        ['question', GATE],
        ['issue', OTHER],
      ],
      { agents: [PLAIN, HEADLESS] },
    )

    await board.annotations.submit('spec-00001-x', {})

    expect(board.sessions.list()[0]).toMatchObject({ kind: 'cowrite', agent: 'plain' })
    expect(board.opened[0]!.agent).toBe('second')
  })

  it('runs the agent each path is given by name', async () => {
    const board = await withAnnotations(
      'draft',
      [
        ['question', GATE],
        ['issue', OTHER],
      ],
      { agents: [PLAIN, HEADLESS] },
    )

    await board.annotations.submit('spec-00001-x', { agents: { question: 'second', cowrite: 'second' } })

    expect(board.sessions.list()[0]).toMatchObject({ kind: 'cowrite', agent: 'second' })
    expect(board.opened[0]!.agent).toBe('second')
  })

  /**
   * The two ways a named agent is no agent for that path: both refuse the whole
   * submit, because it is the request that is wrong (design-00001 §12.3).
   */
  it('refuses the whole submit for an unknown agent and for one that declares no headless form', async () => {
    const board = await withAnnotations('draft', [['question', GATE]], { agents: [PLAIN, HEADLESS] })

    await expect(board.annotations.submit('spec-00001-x', { agents: { cowrite: 'nope' } })).rejects.toMatchObject({
      reason: 'unknown-agent',
    })
    await expect(board.annotations.submit('spec-00001-x', { agents: { question: 'plain' } })).rejects.toMatchObject({
      reason: 'agent-not-headless',
    })
    expect(board.opened).toEqual([])
    expect(board.spawned).toEqual([])
  })

  it('refuses a submit whose fields are not what they have to be', async () => {
    const board = await withAnnotations('draft', [['question', GATE]])

    await expect(board.annotations.submit('spec-00001-x', { unsavedChanges: 'yes' })).rejects.toThrow()
    await expect(board.annotations.submit('spec-00001-x', undefined)).resolves.toMatchObject({ blocked: [] })
    await expect(board.annotations.submit('spec-00001-x', { agents: { question: 3 } })).rejects.toThrow()
  })

  /**
   * spec-00007-AC-10.4 — a second submit of the same document while the first is
   * in flight is refused, and nothing is dispatched twice. The reading is taken
   * before anything touches the disk, which is why the second request can be made
   * in the very same turn.
   */
  // spec-00007-AC-10.4
  it('refuses a second submit of the same document while one is in flight', async () => {
    const board = await withAnnotations('draft', [
      ['question', GATE],
      ['issue', OTHER],
    ])

    const first = board.annotations.submit('spec-00001-x', {})
    const second = await board.annotations.submit('spec-00001-x', {}).catch((cause: unknown) => cause)
    await first

    expect(second).toMatchObject({ reason: 'submit-in-flight' })
    expect(board.opened).toHaveLength(1)
    expect(board.spawned).toHaveLength(1)
  })

  /**
   * spec-00007-AC-10.6 — the document was renamed on disk: the submit is refused
   * whole, by resolving the id rather than by reading a path, and the annotations
   * stay where they are.
   */
  // spec-00007-AC-10.6
  it('refuses the whole submit when the document has been renamed, keeping the annotations', async () => {
    const board = await withAnnotations('draft', [['issue', GATE]])
    writeFileSync(join(board.docsDir, 'spec/x.md'), SPEC('draft').replace('spec-00001-x', 'spec-00002-renamed'))
    renameSync(join(board.docsDir, 'spec/x.md'), join(board.docsDir, 'spec/renamed.md'))

    await expect(board.annotations.submit('spec-00001-x', {})).rejects.toMatchObject({ reason: 'doc-missing' })
    expect(pendingOf(board)).toHaveLength(1)
    expect(board.spawned).toEqual([])
  })

  // spec-00007-AC-4.6 over the submit: an anomalous document is refused whole
  it('refuses the whole submit on an anomalous document', async () => {
    const board = boardOn({ 'spec/broken.md': BROKEN })
    // Recorded while the front matter still read; the file broke afterwards, so
    // the gate on the way in cannot be what holds this one back.
    await board.annotations.store.add('nope', {
      type: 'question',
      text: 'why?',
      anchor: { before: '', selected: GATE, after: '' },
    })

    await expect(board.annotations.submit('nope', {})).rejects.toMatchObject({ reason: 'doc-anomalous' })
  })
})

/**
 * What a submit in flight freezes, and what it does not (spec-00007-FR-10,
 * design-00001 §12.3): the set it is dispatching is read once and then spent over
 * several awaits, so a change or a delete landing in that window would have the
 * batch and the materials built from a text or a selection that is already gone.
 */
describe('while a submit of that document is in flight', () => {
  async function midFlight() {
    const board = boardOn({ 'spec/x.md': SPEC('draft') }, { holdAsk: true })
    for (const [type, selected] of [
      ['question', GATE],
      ['question', OTHER],
    ] as const) {
      await board.annotations.add('spec-00001-x', {
        type,
        text: `${type}: ${selected}`,
        anchor: anchorFor(board.docsDir, 'spec/x.md', selected),
      })
    }
    const submitting = board.annotations.submit('spec-00001-x', {})
    // Held inside the first question's call, which is as mid-flight as it gets.
    await vi.waitFor(() => expect(board.opened).toHaveLength(1))
    return { board, submitting }
  }

  // spec-00007-AC-10.4's other half: the freeze covers the annotations, not only
  // a second submit
  it('refuses a change and a delete of its annotations, and says which refusal it is', async () => {
    const { board, submitting } = await midFlight()

    for (const refused of [
      board.annotations.change('spec-00001-x', 'n-2', { text: 'rewritten mid-flight' }),
      board.annotations.remove('spec-00001-x', 'n-2'),
    ]) {
      await expect(refused).rejects.toMatchObject({ reason: 'submit-in-flight' })
    }

    board.release()
    await submitting
    // And the freeze lifts with the submit.
    expect(board.annotations.list('spec-00001-x').annotations[1]).toMatchObject({ threadId: 't-2' })
  })

  /**
   * Adding is left open: a new annotation is not in this submit's set, so it
   * cannot be built from a stale reading of anything — and refusing the one write
   * that is harmless would only take the entry away mid-read.
   */
  it('takes a new annotation all the same, and leaves it out of this submit', async () => {
    const { board, submitting } = await midFlight()

    const added = await board.annotations.add('spec-00001-x', {
      type: 'question',
      text: 'thought of while it ran',
      anchor: anchorFor(board.docsDir, 'spec/x.md', THIRD),
    })

    board.release()
    const result = await submitting
    expect(result.submitted.questions.map((question) => question.annotationId)).toEqual(['n-1', 'n-2'])
    expect(board.annotations.list('spec-00001-x').annotations.at(-1)).toMatchObject({
      id: added.id,
      state: 'pending',
    })
  })
})

/**
 * The line design-00001 §12.3 draws, on the far side of the transition's write:
 * **4xx means the batch did not happen at all**, so once the document has been
 * moved to `draft` every failure is a per-path outcome in a 200 payload — the
 * transition reported as it went, the issues held back with a reason.
 */
describe('a failure after the transition has written', () => {
  async function withIssue(options?: BoardOptions) {
    const board = boardOn({ 'spec/x.md': SPEC('active') }, options)
    await board.annotations.add('spec-00001-x', {
      type: 'issue',
      text: 'name the gate',
      anchor: anchorFor(board.docsDir, 'spec/x.md', GATE),
    })
    return board
  }

  const draftOnDisk = (board: Board) =>
    readFileSync(join(board.docsDir, 'spec/x.md'), 'utf8').includes('status: draft')

  // The plan cannot be built on the document as it now stands
  it('answers 200 with the issues held back when the session plan could not be built', async () => {
    const board = await withIssue()
    board.docs.annotationCowritePlan = () => {
      throw new WorkflowError('cowrite applies to a draft document (rule-00001-BR-29)')
    }

    const result = await board.annotations.submit('spec-00001-x', {})

    expect(result.transition).toEqual({ to: 'draft', committed: true })
    expect(result.blocked).toEqual([
      { annotationId: 'n-1', reason: 'start-failed', message: expect.stringContaining('rule-00001-BR-29') },
    ])
    expect(result.submitted.issues).toBeNull()
    expect(draftOnDisk(board)).toBe(true)
    expect(board.sessions.list()).toEqual([])
    expect(board.annotations.list('spec-00001-x').annotations[0]).toMatchObject({
      state: 'pending',
      blocked: 'start-failed',
    })
  })

  /**
   * A transition the table refuses — the status having moved on disk since this
   * submit read it — is the eligibility the batch was held to, not a refusal of
   * the request.
   */
  it('answers 200 with the issues held back when the transition itself was refused', async () => {
    const board = await withIssue()
    board.docs.changeStatus = () => Promise.reject(new WorkflowError('active -> draft is not a legal transition'))

    const result = await board.annotations.submit('spec-00001-x', {})

    expect(result.transition).toBeNull()
    expect(result.blocked).toEqual([
      { annotationId: 'n-1', reason: 'gate-ineligible', message: expect.stringContaining('not a legal transition') },
    ])
    expect(draftOnDisk(board)).toBe(false)
  })

  // And when the status lock refuses it, which is the same refusal the judgment
  // stage gives (design-00001 §12.4 (a))
  it('answers 200 with the issues held back when the status lock refused the transition', async () => {
    const board = await withIssue()
    board.docs.changeStatus = () => Promise.reject(new SessionBusyError('spec-00001-x has a running cowrite', 'doc-busy'))

    const result = await board.annotations.submit('spec-00001-x', {})

    expect(result.blocked).toEqual([
      { annotationId: 'n-1', reason: 'doc-busy', message: expect.stringContaining('running cowrite') },
    ])
    expect(result.transition).toBeNull()
  })

  /**
   * Even the reason could not be written down: the answer still stands, because
   * past the transition nothing may turn into a refusal — and the entry says that
   * the record failed rather than swallowing it.
   */
  it('answers 200 when not even the reason could be recorded, and says so', async () => {
    const board = await withIssue()
    board.annotations.store.addBatch = () => Promise.reject(new WorkflowError('the annotations cannot be written'))
    // From the anchor verdicts on: that write is before the transition, where a
    // refusal is still the honest answer.
    const write = board.annotations.store.update.bind(board.annotations.store)
    let turns = 0
    board.annotations.store.update = (docId, change) => {
      turns += 1
      return turns === 1 ? write(docId, change) : Promise.reject(new WorkflowError('the annotations cannot be written'))
    }

    const result = await board.annotations.submit('spec-00001-x', {})

    expect(result.transition).toEqual({ to: 'draft', committed: true })
    expect(result.blocked[0]!.reason).toBe('start-failed')
    expect(result.blocked[0]!.message).toContain('the reason could not be recorded')
    expect(draftOnDisk(board)).toBe(true)
  })

  // The batch row cannot be written: the slot goes back and nothing is left running
  it('answers 200 with the issues held back when the batch row could not be written', async () => {
    const board = await withIssue()
    board.annotations.store.addBatch = () => Promise.reject(new WorkflowError('the annotations cannot be written'))

    const result = await board.annotations.submit('spec-00001-x', {})

    expect(result.transition).toEqual({ to: 'draft', committed: true })
    expect(result.blocked).toEqual([
      { annotationId: 'n-1', reason: 'start-failed', message: expect.stringContaining('cannot be written') },
    ])
    expect(result.submitted.issues).toBeNull()
    expect(draftOnDisk(board)).toBe(true)
    expect(board.annotations.list('spec-00001-x').batches).toEqual([])
    // The slot was given back, so the next submit is not refused for the cap.
    expect(board.sessions.list()[0]).toMatchObject({ status: 'failed' })
  })
})

/**
 * The landing of a batch whose file could not be written (design-00001 §12.6):
 * said rather than swallowed, and retried on the next write of that document —
 * a batch nobody could land would read `cowriting` for ever otherwise.
 */
describe('a batch landing that could not be written', () => {
  it('reports the failure on the session and lands it on the next write of that document', async () => {
    const board = boardOn({ 'spec/x.md': SPEC('draft') })
    await board.annotations.add('spec-00001-x', {
      type: 'issue',
      text: 'name the gate',
      anchor: anchorFor(board.docsDir, 'spec/x.md', GATE),
    })
    await board.annotations.submit('spec-00001-x', {})
    const file = join(board.repoRoot, ANNOTATIONS_DIR, 'spec-00001-x.json')
    const sound = readFileSync(file, 'utf8')
    writeFileSync(file, '{ not json')

    board.exit(0)
    await board.sessions.whenFinished()

    // Nothing was swallowed: the session carries why, apart from a lost transcript.
    expect(board.sessions.latest()!.hookError).toMatch(/cannot be read/)
    expect(board.sessions.latest()!.historyError).toBeUndefined()

    // The file is repaired, and the next write of this document lands the batch.
    writeFileSync(file, sound)
    await board.annotations.add('spec-00001-x', {
      type: 'question',
      text: 'and this?',
      anchor: anchorFor(board.docsDir, 'spec/x.md', OTHER),
    })

    const { annotations, batches } = board.annotations.list('spec-00001-x')
    expect(batches[0]).toMatchObject({ status: 'done', commit: 'abc1234' })
    expect(annotations[0]).toMatchObject({ state: 'submitted', batchId: 'b-1' })
  })

  // A retry that fails again is put back and waits: the landing is not lost by
  // having been tried once.
  it('keeps the landing when the retry fails as well', async () => {
    const board = boardOn({ 'spec/x.md': SPEC('draft') })
    await board.annotations.add('spec-00001-x', {
      type: 'issue',
      text: 'name the gate',
      anchor: anchorFor(board.docsDir, 'spec/x.md', GATE),
    })
    await board.annotations.submit('spec-00001-x', {})
    const file = join(board.repoRoot, ANNOTATIONS_DIR, 'spec-00001-x.json')
    const sound = readFileSync(file, 'utf8')
    writeFileSync(file, '{ not json')
    board.exit(0)
    await board.sessions.whenFinished()

    // Still broken, so the write that would retry it fails too.
    await expect(
      board.annotations.add('spec-00001-x', {
        type: 'question',
        text: 'and this?',
        anchor: anchorFor(board.docsDir, 'spec/x.md', OTHER),
      }),
    ).rejects.toBeInstanceOf(WorkflowError)

    // Repaired, the next write lands it after all.
    writeFileSync(file, sound)
    await board.annotations.add('spec-00001-x', {
      type: 'question',
      text: 'and this?',
      anchor: anchorFor(board.docsDir, 'spec/x.md', OTHER),
    })

    expect(board.annotations.list('spec-00001-x').batches[0]).toMatchObject({ status: 'done' })
  })
})

/**
 * spec-00007-FR-6 — one question is one thread. The annotation is claimed before
 * the call is made and the reference written after it, so a record that fails
 * cannot have the next submit open a second thread on the same question.
 */
describe('a question whose thread could not be written down', () => {
  it('reports it as submitted with a warning, and opens no second thread on a resubmit', async () => {
    const board = boardOn({ 'spec/x.md': SPEC('draft') })
    await board.annotations.add('spec-00001-x', {
      type: 'question',
      text: 'why two gates?',
      anchor: anchorFor(board.docsDir, 'spec/x.md', GATE),
    })
    const write = board.annotations.store.update.bind(board.annotations.store)
    let turns = 0
    board.annotations.store.update = (docId, change) => {
      turns += 1
      // The third turn is the one that writes the thread reference: the anchor
      // verdicts, then the claim, then this.
      return turns === 3 ? Promise.reject(new WorkflowError('the annotations cannot be written')) : write(docId, change)
    }

    const result = await board.annotations.submit('spec-00001-x', {})

    expect(result.submitted.questions).toEqual([{ annotationId: 'n-1', threadId: 't-1', sessionId: expect.any(String) }])
    expect(result.warnings).toEqual([expect.stringContaining('t-1')])
    expect(board.opened).toHaveLength(1)
    // The annotation is out of the unsubmitted region even so, so nothing asks
    // that question a second time.
    const [listed] = board.annotations.list('spec-00001-x').annotations
    expect(listed).toMatchObject({ state: 'submitted' })
    expect(listed!.threadId).toBeUndefined()
    await expect(board.annotations.submit('spec-00001-x', {})).rejects.toMatchObject({ reason: 'empty-submit' })
    expect(board.opened).toHaveLength(1)
  })
})

/**
 * The batch's end, over the registry (design-00001 §12.6): the session's own end
 * state is what the batch records, and the collapse commit rides along.
 */
describe('a batch whose session ends', () => {
  async function withBatch(options?: BoardOptions) {
    const board = boardOn({ 'spec/x.md': SPEC('draft') }, options)
    await board.annotations.add('spec-00001-x', {
      type: 'issue',
      text: 'name the gate',
      anchor: anchorFor(board.docsDir, 'spec/x.md', GATE),
    })
    await board.annotations.submit('spec-00001-x', {})
    return board
  }

  // spec-00007-AC-9.4 — an exit is «done», and the commit is the reference
  it('records a natural end as done, with the collapse commit', async () => {
    const board = await withBatch()

    board.exit(0)
    await board.sessions.whenFinished()

    const { annotations, batches } = board.annotations.list('spec-00001-x')
    expect(batches[0]).toMatchObject({ status: 'done', commit: 'abc1234' })
    expect(batches[0]!.endedAt).toMatch(/^\d{4}-\d{2}-\d{2}T.+Z$/)
    expect(annotations[0]).toMatchObject({ state: 'submitted', batchId: 'b-1' })
  })

  // spec-00007-AC-9.5 — nothing landed, so there is no commit to refer to
  it('records a natural end with nothing committed as done with no commit', async () => {
    const board = await withBatch({ onExit: async () => ({ problems: [], committed: false }) })

    board.exit(0)
    await board.sessions.whenFinished()

    expect(board.annotations.list('spec-00001-x').batches[0]).toMatchObject({ status: 'done', commit: null })
  })

  // An exit is «done» whatever its code: the batch reads the end state and never
  // the reason (design-00001 §12.6)
  it('records a non-zero exit as done all the same', async () => {
    const board = await withBatch()

    board.exit(0, 1)
    await board.sessions.whenFinished()

    expect(board.annotations.list('spec-00001-x').batches[0]!.status).toBe('done')
  })

  /**
   * spec-00007-AC-10.7 — the user stops it: the batch says so, its annotation is
   * back in the unsubmitted region, and it is editable again.
   */
  // spec-00007-AC-10.7
  it('records a stop as terminated and hands the annotation back', async () => {
    const board = await withBatch()

    await board.sessions.terminate(board.sessions.latest()!.id)
    await board.sessions.whenFinished()

    const { annotations, batches } = board.annotations.list('spec-00001-x')
    expect(batches[0]!.status).toBe('terminated')
    expect(annotations[0]!.state).toBe('pending')
    expect(annotations[0]!.batchId).toBeUndefined()
    await expect(board.annotations.change('spec-00001-x', 'n-1', { text: 'clearer now' })).resolves.toMatchObject({
      text: 'clearer now',
    })
  })

  /**
   * spec-00001-FR-16's asynchronous half (design-00001 §12.6): the batch row was
   * already on disk when the seam threw, so the session ends `failed` — and the
   * same end callback hands the annotation back. No rollback of its own.
   */
  it('records a start that failed after the row landed as failed, and hands the annotation back', async () => {
    const board = boardOn({ 'spec/x.md': SPEC('draft') }, { spawnThrows: true })
    await board.annotations.add('spec-00001-x', {
      type: 'issue',
      text: 'name the gate',
      anchor: anchorFor(board.docsDir, 'spec/x.md', GATE),
    })

    const result = await board.annotations.submit('spec-00001-x', {})

    // The answer says what happened rather than claiming a session that failed
    // where it stood: the batch's immediate end state is in the payload.
    expect(result.submitted.issues).toBeNull()
    expect(result.blocked).toEqual([
      { annotationId: 'n-1', reason: 'start-failed', message: expect.stringContaining('the pty seam threw') },
    ])
    const { annotations, batches } = board.annotations.list('spec-00001-x')
    expect(board.sessions.latest()).toMatchObject({ status: 'failed' })
    expect(batches[0]).toMatchObject({ status: 'failed', commit: null })
    expect(annotations[0]!.state).toBe('pending')
    expect(annotations[0]!.batchId).toBeUndefined()
  })
})

/**
 * The precheck of design-00001 §12.4 第 3 步 asks the **same** question the pty
 * seam asks when it starts a session (`unrunnable`), because a second reading
 * would let a submit write the transition and then be refused by the spawn —
 * exactly the landing spec-00007-AC-7.4 forbids.
 */
describe('the executability reading the precheck shares with the spawn', () => {
  /** Whether the real spawner would start this command, the process killed at once. */
  function spawns(command: string, cwd: string): boolean {
    try {
      spawnPty(command, ['-e', ''], cwd).kill()
      return true
    } catch {
      return false
    }
  }

  // spec-00007-AC-7.4's premise: precheck and spawn cannot disagree
  it('answers exactly what the spawner answers, for a command that runs and two that do not', () => {
    const { docsDir } = makeRepo({})
    const unexecutable = join(docsDir, 'notes.md')
    writeFileSync(unexecutable, 'not a program\n')

    for (const command of ['node', 'whiteboard-no-such-command', unexecutable]) {
      expect(unrunnable(command) === undefined).toBe(spawns(command, docsDir))
    }
  })

  it('says why, in the words the blocked reason carries', () => {
    expect(unrunnable('whiteboard-no-such-command')).toMatch(/not found on PATH/)
    expect(unrunnable('/nowhere/at/all')).toMatch(/not executable/)
    expect(unrunnable('node')).toBeUndefined()
  })
})
